package com.whale.crm.exporting.infrastructure;

import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.application.FileContentInspector;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.domain.FileRepository;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class ExportFileService {
    public static final String CLASSIFICATION = "EXPORT";
    private static final String CSV_MIME = "text/csv";
    private static final Logger log = LoggerFactory.getLogger(ExportFileService.class);

    private final FileRepository repository;
    private final ObjectProvider<PrivateObjectStorage> storageProvider;
    private final FileContentInspector inspector;
    private final ClamAvScanner scanner;
    private final FileProperties fileProperties;
    private final ExportProperties exportProperties;

    public ExportFileService(FileRepository repository, ObjectProvider<PrivateObjectStorage> storageProvider,
                             FileContentInspector inspector, ClamAvScanner scanner,
                             FileProperties fileProperties, ExportProperties exportProperties) {
        this.repository = repository;
        this.storageProvider = storageProvider;
        this.inspector = inspector;
        this.scanner = scanner;
        this.fileProperties = fileProperties;
        this.exportProperties = exportProperties;
    }

    public FileMetadata registerCleanFile(ExportJob job, Path temporary, Instant expiresAt) throws Exception {
        PrivateObjectStorage storage = storage();
        FileContentInspector.Inspection inspection = inspector.inspect(temporary);
        if (!CSV_MIME.equals(inspection.mimeType())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出内容不是有效CSV");
        }
        if (inspection.sizeBytes() > fileProperties.getMaxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出文件超过允许大小");
        }
        String objectKey = "export/" + job.getTenantId() + "/" + job.getJobNo() + ".csv";
        FileMetadata existing = repository.findByObjectKey(job.getTenantId(), objectKey).orElse(null);
        if (existing != null) {
            requireMatchingExisting(existing, job, inspection);
            requireClean(scanner.scan(temporary));
            PrivateObjectStorage.ObjectStat stat = storage.stat(objectKey);
            requireStat(stat, inspection);
            return existing;
        }

        boolean uploaded = false;
        try {
            storage.putObject(objectKey, Files.readAllBytes(temporary), CSV_MIME + ";charset=UTF-8");
            uploaded = true;
            PrivateObjectStorage.ObjectStat stat = storage.stat(objectKey);
            requireStat(stat, inspection);
            requireClean(scanner.scan(temporary));
            Instant now = Instant.now();
            FileMetadata input = new FileMetadata(null, job.getTenantId(), objectKey, job.getJobNo() + ".csv",
                    CSV_MIME, inspection.sizeBytes(), inspection.sha256(), CLASSIFICATION, null, "CLEAN",
                    job.getRequestedBy(), stat.etag(), null, expiresAt, now, now, 0L);
            repository.insert(input);
            return repository.findByObjectKey(job.getTenantId(), objectKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "导出文件元数据注册失败"));
        } catch (Exception ex) {
            if (uploaded) {
                deleteObject(storage, objectKey);
            }
            throw ex;
        }
    }

    public Download download(ExportJob job) {
        PrivateObjectStorage storage = storage();
        FileMetadata metadata = repository.findById(job.getTenantId(), job.getFileId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在或不可见"));
        Instant now = Instant.now();
        if (!CLASSIFICATION.equals(metadata.classification()) || metadata.institutionId() != null
                || !"CLEAN".equals(metadata.scanStatus()) || metadata.expiresAt() == null
                || !metadata.expiresAt().isAfter(now) || !Objects.equals(metadata.id(), job.getFileId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件不存在、未通过扫描或已过期");
        }
        long remaining = Math.min(Duration.between(now, job.getExpiresAt()).toSeconds(),
                Duration.between(now, metadata.expiresAt()).toSeconds());
        long ttl = Math.min(exportProperties.getDownloadExpirySeconds(), remaining);
        if (ttl <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导出文件已过期");
        }
        return new Download(storage.presignGet(metadata.objectKey(), Math.toIntExact(ttl)), now.plusSeconds(ttl));
    }

    private PrivateObjectStorage storage() {
        if (!fileProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.CONFLICT, "私有文件服务未配置或已禁用");
        }
        PrivateObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "私有文件存储不可用");
        }
        return storage;
    }

    private void requireMatchingExisting(FileMetadata metadata, ExportJob job,
                                         FileContentInspector.Inspection inspection) {
        if (!CLASSIFICATION.equals(metadata.classification()) || metadata.institutionId() != null
                || !"CLEAN".equals(metadata.scanStatus()) || !Objects.equals(metadata.uploadedBy(), job.getRequestedBy())
                || !Objects.equals(metadata.sha256(), inspection.sha256())
                || metadata.sizeBytes() != inspection.sizeBytes() || metadata.expiresAt() == null
                || !metadata.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "恢复导出时文件元数据不一致");
        }
    }

    private void requireStat(PrivateObjectStorage.ObjectStat stat, FileContentInspector.Inspection inspection) {
        if (stat == null || stat.sizeBytes() != inspection.sizeBytes()) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出对象大小校验失败");
        }
        String etag = normalizeEtag(stat.etag());
        if (etag.length() != 32 || !etag.equalsIgnoreCase(inspection.md5())) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出对象哈希校验失败");
        }
    }

    private void requireClean(ClamAvScanner.ScanResult result) {
        if (result != ClamAvScanner.ScanResult.CLEAN) {
            throw new BusinessException(ErrorCode.CONFLICT, "导出文件未通过ClamAV安全扫描");
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

    private void deleteObject(PrivateObjectStorage storage, String objectKey) {
        try {
            storage.deleteObject(objectKey);
        } catch (RuntimeException ignored) {
            log.warn("导出临时对象清理失败");
        }
    }

    public record Download(String url, Instant expiresAt) {
    }
}
