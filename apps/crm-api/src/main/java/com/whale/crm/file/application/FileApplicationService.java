package com.whale.crm.file.application;

import com.whale.crm.file.api.dto.DownloadResponse;
import com.whale.crm.file.api.dto.FileMetadataResponse;
import com.whale.crm.file.api.dto.UploadCompleteRequest;
import com.whale.crm.file.api.dto.UploadInitRequest;
import com.whale.crm.file.api.dto.UploadInitResponse;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class FileApplicationService {
    private static final Logger log = LoggerFactory.getLogger(FileApplicationService.class);

    private final FileRepository repository;
    private final ObjectProvider<PrivateObjectStorage> storageProvider;
    private final FileContentInspector inspector;
    private final ClamAvScanner scanner;
    private final FileScopeService scopeService;
    private final FileProperties properties;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public FileApplicationService(FileRepository repository, ObjectProvider<PrivateObjectStorage> storageProvider,
                                  FileContentInspector inspector, ClamAvScanner scanner,
                                  FileScopeService scopeService, FileProperties properties,
                                  AuditService auditService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.storageProvider = storageProvider;
        this.inspector = inspector;
        this.scanner = scanner;
        this.scopeService = scopeService;
        this.properties = properties;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UploadInitResponse uploadInit(long tenantId, CurrentUser user, UploadInitRequest request) {
        PrivateObjectStorage storage = storage();
        FileClassification classification = FileClassification.valueOf(request.classification());
        scopeService.requireUpload(user, classification, request.institutionId());
        validateInit(request, classification);
        String objectKey = objectKey(tenantId, classification, request.originalName());
        Instant now = Instant.now();
        FileMetadata input = new FileMetadata(null, tenantId, objectKey, request.originalName(), normalizeDeclaredMime(request.mimeType()),
                request.sizeBytes(), request.sha256().toLowerCase(Locale.ROOT), request.classification(),
                request.institutionId(), "PENDING", user.userId(), null, null, null, now, now, 0L);
        FileMetadata created = Objects.requireNonNull(transactionTemplate.execute(status -> {
            repository.insert(input);
            return repository.findByObjectKey(tenantId, objectKey).orElseThrow();
        }));
        String uploadUrl = storage.presignPut(objectKey, properties.getUploadExpirySeconds());
        auditService.record(tenantId, user.userId(), "FILE_UPLOAD_INIT", "FILE", created.id().toString(),
                null, auditSnapshot(created), classification.name());
        return new UploadInitResponse(created.id(), objectKey, uploadUrl,
                now.plusSeconds(properties.getUploadExpirySeconds()), "PUT", created.sha256());
    }

    public FileMetadataResponse uploadComplete(long tenantId, CurrentUser user, UploadCompleteRequest request) {
        PrivateObjectStorage storage = storage();
        FileMetadata before = requireFile(tenantId, request.fileId());
        FileClassification classification = FileClassification.valueOf(before.classification());
        scopeService.requireUpload(user, classification, before.institutionId());
        if ("CLEAN".equals(before.scanStatus())) {
            requireSameEtag(before, request.etag());
            return FileMetadataResponse.from(before);
        }
        if (!"PENDING".equals(before.scanStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件已完成或正在处理，不能重复完成上传");
        }
        int claimed = Objects.requireNonNull(transactionTemplate.execute(status ->
                repository.startCompletion(tenantId, before.id(), before.version())));
        if (claimed != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件正在由其他上传任务处理");
        }

        Path temporary = null;
        FileMetadata scanned;
        try {
            PrivateObjectStorage.ObjectStat stat = storage.stat(before.objectKey());
            temporary = Files.createTempFile("crm-file-inspect-", ".tmp");
            try (InputStream input = storage.getObject(before.objectKey())) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            FileContentInspector.Inspection inspection = inspector.inspect(temporary,
                    FileClassification.MATERIAL == classification, before.mimeType());
            requireValid(!normalizeEtag(stat.etag()).isEmpty()
                    && normalizeEtag(stat.etag()).equals(normalizeEtag(request.etag())), "ETAG_MISMATCH");
            requireValid(inspection.sizeBytes() == before.sizeBytes() && stat.sizeBytes() == before.sizeBytes(), "SIZE_MISMATCH");
            requireValid(inspection.sha256().equalsIgnoreCase(before.sha256()), "HASH_MISMATCH");
            requireValid(inspection.mimeType().equals(before.mimeType()), "MIME_MISMATCH");
            if (normalizeEtag(stat.etag()).length() == 32) {
                requireValid(normalizeEtag(stat.etag()).equalsIgnoreCase(inspection.md5()), "ETAG_MISMATCH");
            }
            if (classification.pdfOnly()) {
                requireValid("application/pdf".equals(inspection.mimeType()), "INVALID_PDF");
            }
            int updated = Objects.requireNonNull(transactionTemplate.execute(status -> repository.updateUploadMetadata(tenantId, before.id(),
                    before.version() + 1, stat.etag(), inspection.sha256(), inspection.sizeBytes(), inspection.mimeType())));
            if (updated != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "文件元数据已变化，请重新完成上传");
            }
            ClamAvScanner.ScanResult result = scanner.scan(temporary);
            String scanStatus = switch (result) {
                case CLEAN -> "CLEAN";
                case FOUND -> "BLOCKED";
                case ERROR -> "ERROR";
            };
            String lastError = switch (result) {
                case CLEAN -> null;
                case FOUND -> "CLAMAV_FOUND";
                case ERROR -> "CLAMAV_ERROR";
            };
            int marked = Objects.requireNonNull(result == ClamAvScanner.ScanResult.CLEAN
                    ? publishSealed(storage, before, temporary, before.version() + 2, "SCANNING")
                    : transactionTemplate.execute(status -> repository.updateScanStatus(tenantId, before.id(),
                            before.version() + 2, scanStatus, lastError)));
            if (marked != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "文件扫描状态已变化");
            }
            scanned = Objects.requireNonNull(requireFile(tenantId, before.id()));
            auditService.record(tenantId, user.userId(), "FILE_UPLOAD_COMPLETE", "FILE", before.id().toString(),
                null, auditSnapshot(scanned), lastError == null ? classification.name() : lastError);
            if (result != ClamAvScanner.ScanResult.CLEAN) {
                throw new BusinessException(ErrorCode.CONFLICT, "文件未通过安全扫描");
            }
            return FileMetadataResponse.from(scanned);
        } catch (FileContentInspector.FileContentException ex) {
            markCompletionError(before, ex.code());
            throw new BusinessException(ErrorCode.CONFLICT, ex.code());
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            markCompletionError(before, "STORAGE_ERROR");
            throw new BusinessException(ErrorCode.CONFLICT, "对象读取或校验失败");
        } finally {
            deleteTemporary(temporary);
        }
    }

    public FileMetadataResponse metadata(long tenantId, CurrentUser user, long fileId) {
        FileMetadata metadata = requireFile(tenantId, fileId);
        scopeService.requireDownload(user, FileClassification.valueOf(metadata.classification()), metadata.institutionId());
        return FileMetadataResponse.from(metadata);
    }

    public FileMetadataResponse metadataGeneric(long tenantId, CurrentUser user, long fileId) {
        FileMetadata candidate = requireFile(tenantId, fileId);
        requireGenericFileAccess(candidate, false);
        return metadata(tenantId, user, fileId);
    }

    public DownloadResponse downloadGeneric(long tenantId, CurrentUser user, long fileId) {
        FileMetadata candidate = requireFile(tenantId, fileId);
        requireGenericFileAccess(candidate, true);
        return download(tenantId, user, fileId);
    }

    public DownloadResponse download(long tenantId, CurrentUser user, long fileId) {
        PrivateObjectStorage storage = storage();
        FileMetadata metadata = requireCleanUsable(tenantId, user, fileId, (FileClassification) null, null);
        String url = storage.presignGet(metadata.objectKey(), properties.getDownloadExpirySeconds());
        auditService.record(tenantId, user.userId(), "FILE_DOWNLOAD", "FILE", Long.toString(fileId),
                null, auditSnapshot(metadata), metadata.classification());
        return new DownloadResponse(fileId, url, Instant.now().plusSeconds(properties.getDownloadExpirySeconds()));
    }

    private void requireGenericFileAccess(FileMetadata metadata, boolean download) {
        FileClassification classification = FileClassification.valueOf(metadata.classification());
        if (classification == FileClassification.EXPORT) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "导出文件必须通过导出任务专用接口访问");
        }
        if (download && classification == FileClassification.MATERIAL) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "素材必须通过素材修订专用接口下载");
        }
    }

    public FileMetadata requireCleanUsable(long tenantId, CurrentUser user, long fileId,
                                           String expectedClassification, Long institutionId) {
        FileClassification classification = expectedClassification == null ? null : FileClassification.valueOf(expectedClassification);
        return requireCleanUsable(tenantId, user, fileId, classification, institutionId);
    }

    public FileMetadata requireCleanUsable(long tenantId, CurrentUser user, long fileId,
                                           FileClassification expectedClassification, Long institutionId) {
        FileMetadata metadata = requireFile(tenantId, fileId);
        FileClassification actual = FileClassification.valueOf(metadata.classification());
        if (expectedClassification != null && actual != expectedClassification) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件类别不匹配");
        }
        boolean materialScopeMismatch = expectedClassification == FileClassification.MATERIAL
                && !Objects.equals(institutionId, metadata.institutionId());
        boolean requestedInstitutionMismatch = institutionId != null
                && !Objects.equals(institutionId, metadata.institutionId());
        if (materialScopeMismatch || requestedInstitutionMismatch) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "文件机构归属不匹配");
        }
        if (!"CLEAN".equals(metadata.scanStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件尚未通过安全扫描");
        }
        if (metadata.expiresAt() != null && metadata.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件已过期");
        }
        scopeService.requireDownload(user, actual, metadata.institutionId());
        return ensureSealed(metadata);
    }

    public KnowledgeTextContent readKnowledgeText(long tenantId, long fileId, Long institutionId, long maxBytes) {
        long limit = Math.min(maxBytes, 1_048_576L);
        if (limit <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "知识文本大小限制无效");
        }
        PrivateObjectStorage storage = storage();
        FileMetadata metadata = requireFile(tenantId, fileId);
        if (FileClassification.MATERIAL != FileClassification.valueOf(metadata.classification())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有 MATERIAL 文件可以作为知识资料");
        }
        if (!Objects.equals(institutionId, metadata.institutionId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "文件机构归属不匹配");
        }
        if (!"CLEAN".equals(metadata.scanStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件尚未通过安全扫描");
        }
        if (metadata.expiresAt() != null && metadata.expiresAt().isBefore(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "文件已过期");
        }
        if (!java.util.Set.of("text/plain", "text/markdown", "text/x-markdown").contains(metadata.mimeType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识资料仅支持 UTF-8 TXT/Markdown");
        }
        if (metadata.sizeBytes() != null && metadata.sizeBytes() > limit) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识文本不能超过 1 MiB");
        }
        metadata = ensureSealed(metadata);
        try (InputStream input = storage.getObject(metadata.objectKey())) {
            byte[] bytes = input.readNBytes(Math.toIntExact(limit) + 1);
            if (bytes.length > limit) {
                throw new BusinessException(ErrorCode.CONFLICT, "知识文本不能超过 1 MiB");
            }
            String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return new KnowledgeTextContent(metadata, text);
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识文本必须是有效 UTF-8");
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识文本读取失败");
        }
    }

    private FileMetadata ensureSealed(FileMetadata metadata) {
        if (metadata.classification().equals("EXPORT") || metadata.objectKey().startsWith("sealed/")) {
            return metadata;
        }
        PrivateObjectStorage storage = storage();
        Path temporary = null;
        try {
            temporary = Files.createTempFile("crm-file-legacy-", ".tmp");
            try (InputStream input = storage.getObject(metadata.objectKey())) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            FileClassification classification = FileClassification.valueOf(metadata.classification());
            FileContentInspector.Inspection inspection = inspector.inspect(temporary,
                    classification == FileClassification.MATERIAL, metadata.mimeType());
            requireValid(inspection.sizeBytes() == metadata.sizeBytes(), "SIZE_MISMATCH");
            requireValid(inspection.sha256().equalsIgnoreCase(metadata.sha256()), "HASH_MISMATCH");
            requireValid(inspection.mimeType().equals(metadata.mimeType()), "MIME_MISMATCH");
            requireValid(!classification.pdfOnly() || "application/pdf".equals(inspection.mimeType()), "INVALID_PDF");
            requireValid(scanner.scan(temporary) == ClamAvScanner.ScanResult.CLEAN, "SCAN_FAILED");
            int updated = publishSealed(storage, metadata, temporary, metadata.version(), "CLEAN");
            if (updated != 1) {
                FileMetadata current = requireFile(metadata.tenantId(), metadata.id());
                if ("CLEAN".equals(current.scanStatus()) && current.objectKey().startsWith("sealed/")) {
                    return current;
                }
                throw new BusinessException(ErrorCode.CONFLICT, "文件状态已变化");
            }
            return requireFile(metadata.tenantId(), metadata.id());
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | FileContentInspector.FileContentException | RuntimeException ex) {
            log.warn("旧文件安全校验失败 fileId={}", metadata.id(), ex);
            throw new BusinessException(ErrorCode.CONFLICT, "旧文件安全校验失败");
        } finally {
            deleteTemporary(temporary);
        }
    }

    private int publishSealed(PrivateObjectStorage storage, FileMetadata metadata, Path content,
                              long expectedVersion, String expectedStatus) throws IOException {
        String key = "sealed/" + metadata.tenantId() + "/" + UUID.randomUUID();
        boolean published = false;
        try {
            try (InputStream input = Files.newInputStream(content)) {
                storage.putObject(key, input, Files.size(content), metadata.mimeType());
            }
            int updated = Objects.requireNonNull(transactionTemplate.execute(status -> repository.publishSealed(metadata.tenantId(),
                    metadata.id(), expectedVersion, expectedStatus, key)));
            published = updated == 1;
            return updated;
        } finally {
            if (!published) {
                try {
                    storage.deleteObject(key);
                } catch (RuntimeException ignored) {
                    log.warn("清理未发布私有对象失败");
                }
            }
        }
    }

    private PrivateObjectStorage storage() {
        if (!properties.isEnabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "私有文件服务未配置或已禁用");
        }
        PrivateObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "私有文件存储不可用");
        }
        return storage;
    }

    private FileMetadata requireFile(long tenantId, long fileId) {
        return repository.findById(tenantId, fileId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文件不存在或不在数据范围内"));
    }

    private void validateInit(UploadInitRequest request, FileClassification classification) {
        if (request.sizeBytes() > properties.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件超过允许大小");
        }
        if (classification.pdfOnly() && !"application/pdf".equals(request.mimeType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该文件类别仅接受PDF");
        }
        String name = request.originalName();
        if (name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文件名不合法");
        }
    }

    private String normalizeDeclaredMime(String mimeType) {
        return "text/x-markdown".equals(mimeType) ? "text/markdown" : mimeType;
    }

    private String objectKey(long tenantId, FileClassification classification, String originalName) {
        String extension = originalName.toLowerCase(Locale.ROOT).endsWith(".pdf") ? ".pdf" : "";
        return classification.directory() + "/" + tenantId + "/" + UUID.randomUUID() + extension;
    }

    private void requireSameEtag(FileMetadata metadata, String requestEtag) {
        String expected = normalizeEtag(metadata.etag());
        String actual = normalizeEtag(requestEtag);
        if (expected.isEmpty() || !expected.equals(actual)) {
            throw new BusinessException(ErrorCode.CONFLICT, "ETAG_MISMATCH");
        }
    }

    private String normalizeEtag(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        return normalized.replace("\"", "");
    }

    private void requireValid(boolean condition, String code) throws FileContentInspector.FileContentException {
        if (!condition) {
            throw new FileContentInspector.FileContentException(code);
        }
    }

    private void markCompletionError(FileMetadata before, String code) {
        transactionTemplate.execute(status -> {
            int updated = repository.updateScanStatus(before.tenantId(), before.id(), before.version() + 1, "ERROR", code);
            if (updated == 0) {
                repository.updateScanStatus(before.tenantId(), before.id(), before.version() + 2, "ERROR", code);
            }
            return null;
        });
    }

    private Map<String, Object> auditSnapshot(FileMetadata metadata) {
        return Map.of("fileId", metadata.id(), "classification", metadata.classification(),
                "scanStatus", metadata.scanStatus());
    }

    private void deleteTemporary(Path temporary) {
        if (temporary != null) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                log.warn("清理临时文件失败");
            }
        }
    }
}
