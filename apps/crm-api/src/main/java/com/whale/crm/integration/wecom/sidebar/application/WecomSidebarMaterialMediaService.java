package com.whale.crm.integration.wecom.sidebar.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.file.infrastructure.FileProperties;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomSidebarMaterialMediaResponse;
import com.whale.crm.material.api.dto.MaterialResponse;
import com.whale.crm.material.api.dto.MaterialRevisionResponse;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 企微侧边栏素材库文件换取企微临时素材 media_id 服务。
 * 严格校验租户、官方企微/私有文件存储配置、素材审批生效状态、文件安全扫描状态与企微大小限制，
 * 绝不伪造官方 media_id。
 */
@Service
public class WecomSidebarMaterialMediaService {
    private static final String CACHE_KEY_PREFIX = "wecom:temp_media:";
    private static final Duration CACHE_TTL = Duration.ofHours(60);
    private static final long MIN_MEDIA_BYTES = 5L;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final Set<String> IMAGE_MIMES = Set.of("image/jpeg", "image/png");
    private static final Set<String> FILE_MIMES = Set.of("application/pdf", "text/plain", "text/markdown", "text/csv");

    private final MaterialApplicationService materialService;
    private final FileApplicationService fileService;
    private final ObjectProvider<PrivateObjectStorage> storageProvider;
    private final FileProperties fileProperties;
    private final WecomOfficialProperties officialProperties;
    private final WecomSidebarProperties sidebarProperties;
    private final IntegrationRuntimeConfigService runtimeConfigService;
    private final WecomSidebarClient sidebarClient;
    private final StringRedisTemplate redis;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public WecomSidebarMaterialMediaService(MaterialApplicationService materialService,
                                            FileApplicationService fileService,
                                            ObjectProvider<PrivateObjectStorage> storageProvider,
                                            FileProperties fileProperties,
                                            WecomOfficialProperties officialProperties,
                                            WecomSidebarProperties sidebarProperties,
                                            IntegrationRuntimeConfigService runtimeConfigService,
                                            WecomSidebarClient sidebarClient,
                                            StringRedisTemplate redis,
                                            AuditService auditService,
                                            ObjectMapper objectMapper) {
        this.materialService = materialService;
        this.fileService = fileService;
        this.storageProvider = storageProvider;
        this.fileProperties = fileProperties;
        this.officialProperties = officialProperties;
        this.sidebarProperties = sidebarProperties;
        this.runtimeConfigService = runtimeConfigService;
        this.sidebarClient = sidebarClient;
        this.redis = redis;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public WecomSidebarMaterialMediaResponse uploadMaterialMedia(CurrentUser user, long materialId) {
        long tenantId = user.tenantId();
        requireOfficialWecomConfigured(tenantId);
        PrivateObjectStorage storage = requireObjectStorage();

        MaterialResponse material = materialService.detail(tenantId, user, materialId);
        if (material.offlineAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已下架，无法发送到企微");
        }
        Long currentRevisionId = material.currentRevisionId();
        if (currentRevisionId == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已审核通过的生效素材可以发送到企微");
        }
        MaterialRevisionResponse approvedRevision = material.revisions() == null ? null : material.revisions().stream()
                .filter(rev -> Objects.equals(rev.id(), currentRevisionId) && "APPROVED".equals(rev.status()))
                .findFirst().orElse(null);
        if (approvedRevision == null || approvedRevision.fileId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已审核通过的生效素材可以发送到企微");
        }

        FileMetadata metadata = fileService.requireCleanUsable(tenantId, user, approvedRevision.fileId(),
                FileClassification.MATERIAL, approvedRevision.institutionId());
        MediaRule rule = validateMediaRule(metadata);

        String cacheKey = cacheKey(tenantId, materialId);
        CachedTempMedia cached = readCache(cacheKey);
        if (cached != null
                && cached.revisionId() == approvedRevision.id()
                && cached.fileId() == metadata.id()
                && cached.mediaId() != null && !cached.mediaId().isBlank()
                && rule.mediaType().equals(cached.mediaType())) {
            audit(tenantId, user.userId(), materialId, approvedRevision.id(), metadata.id(), rule.mediaType(), true);
            Instant cachedExpiresAt = cached.expiresAtEpochMillis() > 0
                    ? Instant.ofEpochMilli(cached.expiresAtEpochMillis()) : Instant.now().plus(CACHE_TTL);
            return new WecomSidebarMaterialMediaResponse(materialId, approvedRevision.id(), metadata.id(),
                    cached.mediaId(), cached.mediaType(), metadata.originalName(), rule.mimeType(),
                    metadata.sizeBytes(), true, cachedExpiresAt);
        }

        byte[] bytes = readBoundedObject(storage, metadata.objectKey(), rule.maxBytes(), rule.mediaType());
        WecomSidebarClient.WecomUploadedMedia uploaded = sidebarClient.uploadTempMedia(
                rule.mediaType(), metadata.originalName(), rule.mimeType(), bytes);
        Instant expiresAt = Instant.now().plus(CACHE_TTL);
        CachedTempMedia nextCache = new CachedTempMedia(approvedRevision.id(), metadata.id(), uploaded.mediaId(),
                rule.mediaType(), expiresAt.toEpochMilli());
        writeCache(cacheKey, nextCache);
        audit(tenantId, user.userId(), materialId, approvedRevision.id(), metadata.id(), rule.mediaType(), false);
        return new WecomSidebarMaterialMediaResponse(materialId, approvedRevision.id(), metadata.id(),
                uploaded.mediaId(), rule.mediaType(), metadata.originalName(), rule.mimeType(),
                bytes.length, false, expiresAt);
    }

    private void requireOfficialWecomConfigured(long tenantId) {
        if (officialProperties.allowedTenantId() <= 0 || tenantId != officialProperties.allowedTenantId()
                || !sidebarProperties.enabled()
                || !runtimeConfigService.wecomOfficialEnabled(tenantId)
                || officialProperties.corpId() == null || officialProperties.corpId().isBlank()
                || officialProperties.secret() == null || officialProperties.secret().isBlank()
                || officialProperties.apiBaseUrl() == null || officialProperties.apiBaseUrl().isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微官方应用或侧边栏尚未启用，无法上传临时素材");
        }
    }

    private PrivateObjectStorage requireObjectStorage() {
        if (!fileProperties.isEnabled()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "私有文件存储未启用，无法上传素材到企微");
        }
        PrivateObjectStorage storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "私有文件存储尚未配置，无法上传素材到企微");
        }
        return storage;
    }

    private MediaRule validateMediaRule(FileMetadata metadata) {
        String mime = metadata.mimeType() == null ? "" : metadata.mimeType().strip().toLowerCase(Locale.ROOT);
        String mediaType;
        long maxBytes;
        if (IMAGE_MIMES.contains(mime)) {
            mediaType = "image";
            maxBytes = MAX_IMAGE_BYTES;
        } else if (FILE_MIMES.contains(mime)) {
            mediaType = "file";
            maxBytes = MAX_FILE_BYTES;
        } else {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅支持发送 JPG/PNG 图片或 PDF/文档类素材到企微");
        }
        Long size = metadata.sizeBytes();
        if (size == null || size < MIN_MEDIA_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材文件为空或小于企微最小限制（5字节）");
        }
        if (size > maxBytes) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "image".equals(mediaType) ? "图片素材超过企微 10MB 大小限制" : "文件素材超过企微 20MB 大小限制");
        }
        return new MediaRule(mediaType, mime, maxBytes);
    }

    private byte[] readBoundedObject(PrivateObjectStorage storage, String objectKey, long maxBytes, String mediaType) {
        try (InputStream input = storage.getObject(objectKey)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maxBytes) + 1);
            if (bytes.length < MIN_MEDIA_BYTES) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材文件为空或小于企微最小限制（5字节）");
            }
            if (bytes.length > maxBytes) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "image".equals(mediaType) ? "图片素材超过企微 10MB 大小限制" : "文件素材超过企微 20MB 大小限制");
            }
            return bytes;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材文件读取失败，请稍后重试");
        }
    }

    private CachedTempMedia readCache(String key) {
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微临时素材缓存暂不可用");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, CachedTempMedia.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String key, CachedTempMedia entry) {
        try {
            String serialized = objectMapper.writeValueAsString(entry);
            redis.opsForValue().set(key, serialized, CACHE_TTL);
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微临时素材缓存暂不可用");
        } catch (Exception ex) {
            throw new IllegalStateException("序列化企微临时素材缓存失败", ex);
        }
    }

    private void audit(long tenantId, long userId, long materialId, long revisionId, long fileId,
                       String mediaType, boolean cached) {
        auditService.record(tenantId, userId, "WECOM_SIDEBAR_MATERIAL_MEDIA_PREPARE", "MATERIAL",
                Long.toString(materialId), null, Map.of(
                        "materialId", materialId,
                        "revisionId", revisionId,
                        "fileId", fileId,
                        "mediaType", mediaType,
                        "cached", cached), null);
    }

    private String cacheKey(long tenantId, long materialId) {
        return CACHE_KEY_PREFIX + tenantId + ":" + materialId;
    }

    private record MediaRule(String mediaType, String mimeType, long maxBytes) {
    }

    private record CachedTempMedia(long revisionId, long fileId, String mediaId, String mediaType,
                                   long expiresAtEpochMillis) {
    }
}
