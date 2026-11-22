package com.whale.crm.integration.wecom;

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
import com.whale.crm.integration.wecom.sidebar.application.WecomSidebarMaterialMediaService;
import com.whale.crm.material.api.dto.MaterialResponse;
import com.whale.crm.material.api.dto.MaterialRevisionResponse;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomSidebarMaterialMediaServiceTest {
    private MaterialApplicationService materialService;
    private FileApplicationService fileService;
    private ObjectProvider<PrivateObjectStorage> storageProvider;
    private PrivateObjectStorage storage;
    private FileProperties fileProperties;
    private WecomOfficialProperties officialProperties;
    private WecomSidebarProperties sidebarProperties;
    private IntegrationRuntimeConfigService runtimeConfigService;
    private WecomSidebarClient sidebarClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuditService auditService;
    private Map<String, String> redisStore;

    private final CurrentUser steward = new CurrentUser(2L, 1L, "manager1", "管家一", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("wecom:sidebar:view", "material:read"), Set.of());

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        materialService = mock(MaterialApplicationService.class);
        fileService = mock(FileApplicationService.class);
        storageProvider = mock(ObjectProvider.class);
        storage = mock(PrivateObjectStorage.class);
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        fileProperties = new FileProperties();
        fileProperties.setEnabled(true);

        officialProperties = new WecomOfficialProperties(1L, true, "ww-corp", "secret",
                "https://qyapi.weixin.qq.com", "", "", 3000, 3000, 100, 10);
        sidebarProperties = new WecomSidebarProperties(true, 10001L,
                "https://open.weixin.qq.com/connect/oauth2/authorize",
                "https://crm.example.com/api/v1/wecom/oauth/callback",
                "https://crm.example.com/wecom/sidebar",
                List.of("https://crm.example.com"), 300, 60);
        runtimeConfigService = mock(IntegrationRuntimeConfigService.class);
        when(runtimeConfigService.wecomOfficialEnabled(1L)).thenReturn(true);

        sidebarClient = mock(WecomSidebarClient.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        redisStore = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));
        doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        auditService = mock(AuditService.class);
    }

    private WecomSidebarMaterialMediaService service() {
        return new WecomSidebarMaterialMediaService(materialService, fileService, storageProvider, fileProperties,
                officialProperties, sidebarProperties, runtimeConfigService, sidebarClient, redis, auditService,
                new ObjectMapper());
    }

    @Test
    void uploadsApprovedImageCachesInRedisFor60HoursAndServesCacheHit() {
        MaterialResponse material = material(10L, 21L, 35L, "APPROVED", null);
        byte[] content = "PNG-BYTES-123456".getBytes(StandardCharsets.UTF_8);
        FileMetadata file = fileMetadata(35L, "sealed/1/img.png", "poster.png", "image/png", content.length);

        when(materialService.detail(1L, steward, 10L)).thenReturn(material);
        when(fileService.requireCleanUsable(1L, steward, 35L, FileClassification.MATERIAL, null)).thenReturn(file);
        when(storage.getObject("sealed/1/img.png")).thenReturn(new ByteArrayInputStream(content));
        when(sidebarClient.uploadTempMedia("image", "poster.png", "image/png", content))
                .thenReturn(new WecomSidebarClient.WecomUploadedMedia("MEDIA-IMG-99", "image", Instant.now()));

        WecomSidebarMaterialMediaService svc = service();
        WecomSidebarMaterialMediaResponse first = svc.uploadMaterialMedia(steward, 10L);

        assertThat(first.mediaId()).isEqualTo("MEDIA-IMG-99");
        assertThat(first.mediaType()).isEqualTo("image");
        assertThat(first.cached()).isFalse();
        assertThat(first.sizeBytes()).isEqualTo(content.length);
        verify(valueOps).set(eq("wecom:temp_media:1:10"), anyString(), eq(Duration.ofHours(60)));

        WecomSidebarMaterialMediaResponse second = svc.uploadMaterialMedia(steward, 10L);
        assertThat(second.mediaId()).isEqualTo("MEDIA-IMG-99");
        assertThat(second.cached()).isTrue();
        verify(sidebarClient, times(1)).uploadTempMedia(anyString(), anyString(), anyString(), any());
        verify(storage, times(1)).getObject("sealed/1/img.png");
    }

    @Test
    void uploadsPdfAsFileAndRefreshesCacheWhenApprovedRevisionChanges() {
        byte[] pdfV1 = "%PDF-1.4\nv1-content\n%%EOF".getBytes(StandardCharsets.UTF_8);
        byte[] pdfV2 = "%PDF-1.4\nv2-updated-content\n%%EOF".getBytes(StandardCharsets.UTF_8);

        when(materialService.detail(1L, steward, 11L)).thenReturn(
                material(11L, 101L, 201L, "APPROVED", null),
                material(11L, 102L, 202L, "APPROVED", null));
        when(fileService.requireCleanUsable(1L, steward, 201L, FileClassification.MATERIAL, null))
                .thenReturn(fileMetadata(201L, "sealed/1/v1.pdf", "guide-v1.pdf", "application/pdf", pdfV1.length));
        when(fileService.requireCleanUsable(1L, steward, 202L, FileClassification.MATERIAL, null))
                .thenReturn(fileMetadata(202L, "sealed/1/v2.pdf", "guide-v2.pdf", "application/pdf", pdfV2.length));
        when(storage.getObject("sealed/1/v1.pdf")).thenReturn(new ByteArrayInputStream(pdfV1));
        when(storage.getObject("sealed/1/v2.pdf")).thenReturn(new ByteArrayInputStream(pdfV2));
        when(sidebarClient.uploadTempMedia("file", "guide-v1.pdf", "application/pdf", pdfV1))
                .thenReturn(new WecomSidebarClient.WecomUploadedMedia("MEDIA-PDF-1", "file", Instant.now()));
        when(sidebarClient.uploadTempMedia("file", "guide-v2.pdf", "application/pdf", pdfV2))
                .thenReturn(new WecomSidebarClient.WecomUploadedMedia("MEDIA-PDF-2", "file", Instant.now()));

        WecomSidebarMaterialMediaService svc = service();
        WecomSidebarMaterialMediaResponse first = svc.uploadMaterialMedia(steward, 11L);
        WecomSidebarMaterialMediaResponse second = svc.uploadMaterialMedia(steward, 11L);

        assertThat(first.mediaId()).isEqualTo("MEDIA-PDF-1");
        assertThat(first.mediaType()).isEqualTo("file");
        assertThat(second.mediaId()).isEqualTo("MEDIA-PDF-2");
        assertThat(second.revisionId()).isEqualTo(102L);
        assertThat(second.cached()).isFalse();
    }

    @Test
    void throwsPendingBusinessRuleWhenOfficialWecomOrFileStorageDisabled() {
        when(runtimeConfigService.wecomOfficialEnabled(1L)).thenReturn(false);
        assertThatThrownBy(() -> service().uploadMaterialMedia(steward, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);

        when(runtimeConfigService.wecomOfficialEnabled(1L)).thenReturn(true);
        fileProperties.setEnabled(false);
        assertThatThrownBy(() -> service().uploadMaterialMedia(steward, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);

        fileProperties.setEnabled(true);
        when(storageProvider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service().uploadMaterialMedia(steward, 10L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.PENDING_BUSINESS_RULE);

        verify(sidebarClient, never()).uploadTempMedia(anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsOfflineUnapprovedOversizedOrUnsupportedMimeMaterials() {
        WecomSidebarMaterialMediaService svc = service();

        // Offline material
        when(materialService.detail(1L, steward, 12L))
                .thenReturn(material(12L, 30L, 40L, "APPROVED", Instant.now()));
        assertThatThrownBy(() -> svc.uploadMaterialMedia(steward, 12L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // Unapproved draft material (currentRevisionId == null)
        when(materialService.detail(1L, steward, 13L))
                .thenReturn(material(13L, null, 41L, "DRAFT", null));
        assertThatThrownBy(() -> svc.uploadMaterialMedia(steward, 13L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // Image > 10MB
        when(materialService.detail(1L, steward, 14L))
                .thenReturn(material(14L, 32L, 42L, "APPROVED", null));
        when(fileService.requireCleanUsable(1L, steward, 42L, FileClassification.MATERIAL, null))
                .thenReturn(fileMetadata(42L, "sealed/1/big.png", "big.png", "image/png", 10L * 1024 * 1024 + 1));
        assertThatThrownBy(() -> svc.uploadMaterialMedia(steward, 14L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Unsupported MIME
        when(materialService.detail(1L, steward, 15L))
                .thenReturn(material(15L, 33L, 43L, "APPROVED", null));
        when(fileService.requireCleanUsable(1L, steward, 43L, FileClassification.MATERIAL, null))
                .thenReturn(fileMetadata(43L, "sealed/1/vid.mp4", "vid.mp4", "video/mp4", 1024L));
        assertThatThrownBy(() -> svc.uploadMaterialMedia(steward, 15L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // Redis failure
        when(materialService.detail(1L, steward, 16L))
                .thenReturn(material(16L, 34L, 44L, "APPROVED", null));
        when(fileService.requireCleanUsable(1L, steward, 44L, FileClassification.MATERIAL, null))
                .thenReturn(fileMetadata(44L, "sealed/1/ok.png", "ok.png", "image/png", 1024L));
        when(valueOps.get("wecom:temp_media:1:16")).thenThrow(new DataAccessResourceFailureException("redis error"));
        assertThatThrownBy(() -> svc.uploadMaterialMedia(steward, 16L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.AUTH_PROTECTION_UNAVAILABLE);
    }

    private MaterialResponse material(long id, Long currentRevisionId, long fileId, String revStatus, Instant offlineAt) {
        MaterialRevisionResponse rev = new MaterialRevisionResponse(
                currentRevisionId == null ? 999L : currentRevisionId, id, 1, "项目海报", "摘要", "POSTER",
                null, null, null, List.of(), fileId, null, revStatus, 1L, null, Instant.now(), 1L,
                Instant.now(), Instant.now(), 0L);
        return new MaterialResponse(id, "MT" + id, "项目海报", "POSTER", null, null, null, "摘要",
                List.of(), currentRevisionId, revStatus, offlineAt, null, null, 1L,
                Instant.now(), Instant.now(), 0L, List.of(rev));
    }

    private FileMetadata fileMetadata(long id, String objectKey, String originalName, String mimeType, long size) {
        return new FileMetadata(id, 1L, objectKey, originalName, mimeType, size, "sha256",
                "MATERIAL", null, "CLEAN", 1L, "etag", null, null, Instant.now(), Instant.now(), 0L);
    }
}
