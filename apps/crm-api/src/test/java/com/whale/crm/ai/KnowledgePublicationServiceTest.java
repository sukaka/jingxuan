package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.KnowledgeSyncJobResponse;
import com.whale.crm.ai.api.dto.PublishKnowledgeDocumentRequest;
import com.whale.crm.ai.application.DifyKnowledgeConfigService;
import com.whale.crm.ai.application.KnowledgePublicationService;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.application.KnowledgeTextContent;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.material.application.MaterialApplicationService;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgePublicationServiceTest {
    private static final String HASH = "a".repeat(64);
    @Mock KnowledgeRepository repository;
    @Mock MaterialApplicationService materialService;
    @Mock FileApplicationService fileService;
    @Mock DifyKnowledgeConfigService configService;
    @Mock OutboxService outboxService;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;
    @Mock PlatformTransactionManager transactionManager;

    private KnowledgePublicationService service;
    private final CurrentUser admin = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("ai:knowledge:publish"), Set.of());

    @BeforeEach
    void setUp() {
        service = new KnowledgePublicationService(repository, materialService, fileService, configService,
                outboxService, idempotencyService, auditService, new ObjectMapper().findAndRegisterModules(),
                transactionManager);
    }

    @Test
    void approvedCleanTextCreatesOnlyWaitingJobAndMinimalOutbox() {
        stubEnabledSource();
        KnowledgeDocument document = document();
        KnowledgeSyncJob job = job();
        when(repository.findDocumentForUpdate(1L, 10L)).thenReturn(Optional.empty(), Optional.of(document));
        when(repository.findSyncJobByContent(1L, 101L, 20L, HASH)).thenReturn(Optional.empty(), Optional.of(job));

        KnowledgeSyncJobResponse response = service.publish(admin, new PublishKnowledgeDocumentRequest(10L, 20L, 2L), "publish-1");

        assertThat(response.id()).isEqualTo(501L);
        assertThat(response.status()).isEqualTo("WAITING");
        verify(repository).insertDocument(eq(1L), any());
        verify(repository).insertSyncJob(eq(1L), any());
        verify(outboxService).append(eq(1L), eq("knowledge.sync.requested.v1:501"),
                eq("knowledge.sync.requested.v1"), eq("knowledge_document"), eq("501"),
                eq(Map.of("jobId", 501L, "documentId", 101L)));
        verify(auditService).record(eq(1L), eq(1L), eq("KNOWLEDGE_PUBLISH_REQUEST"), eq("KNOWLEDGE_SYNC_JOB"),
                eq("501"), any(), any(), any());
    }

    @Test
    void sameRevisionAndHashReturnsExistingJobWithoutDuplicateOutbox() {
        stubEnabledSource();
        when(repository.findDocumentForUpdate(1L, 10L)).thenReturn(Optional.of(document()));
        when(repository.findSyncJobByContent(1L, 101L, 20L, HASH)).thenReturn(Optional.of(job()));

        KnowledgeSyncJobResponse response = service.publish(admin, new PublishKnowledgeDocumentRequest(10L, 20L, 2L), "publish-2");

        assertThat(response.id()).isEqualTo(501L);
        verify(repository, never()).insertSyncJob(anyLong(), any());
        verify(outboxService, never()).append(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void disabledDocumentCannotBeImplicitlyRepublished() {
        stubEnabledSource();
        KnowledgeDocument disabled = new KnowledgeDocument(101L, 1L, 10L, 20L, "dataset-1", "doc-1",
                "DISABLED", 1L, Instant.now(), Instant.now(), Instant.now(), 4L);
        when(repository.findDocumentForUpdate(1L, 10L)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.publish(admin, new PublishKnowledgeDocumentRequest(10L, 20L, 2L), "republish"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已停用");

        verify(repository, never()).insertSyncJob(anyLong(), any());
        verify(outboxService, never()).append(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void disabledSyncAndStaleRevisionAreRejectedBeforeWriting() {
        when(configService.syncEnabled(1L)).thenReturn(false);
        assertThatThrownBy(() -> service.publish(admin, new PublishKnowledgeDocumentRequest(10L, 20L, 2L), "disabled"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未启用");
        verify(materialService, never()).getApprovedRevisionForKnowledge(anyLong(), any(), anyLong(), anyLong());

        when(configService.syncEnabled(1L)).thenReturn(true);
        when(materialService.getApprovedRevisionForKnowledge(1L, admin, 10L, 20L)).thenReturn(revision());
        assertThatThrownBy(() -> service.publish(admin, new PublishKnowledgeDocumentRequest(10L, 20L, 1L), "stale"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本");
        verify(fileService, never()).readKnowledgeText(anyLong(), anyLong(), any(), anyLong());
        verify(outboxService, never()).append(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    private void stubEnabledSource() {
        when(configService.syncEnabled(1L)).thenReturn(true);
        when(configService.datasetId(1L)).thenReturn("dataset-1");
        when(materialService.getApprovedRevisionForKnowledge(1L, admin, 10L, 20L)).thenReturn(revision());
        FileMetadata metadata = new FileMetadata(99L, 1L, "material/1/guide", "guide.md", "text/markdown",
                100L, HASH, "MATERIAL", 1L, "CLEAN", 7L, "etag", null, null,
                Instant.now(), Instant.now(), 2L);
        when(fileService.readKnowledgeText(1L, 99L, 1L, 1_048_576L))
                .thenReturn(new KnowledgeTextContent(metadata, "# 合成服务流程\n仅供测试"));
    }

    private MaterialRevision revision() {
        return new MaterialRevision(20L, 1L, 10L, 2, "合成服务流程", "摘要", "GUIDE", 1L, 30L,
                "310100", "[]", 99L, null, "APPROVED", 8L, null, Instant.now(), 7L,
                Instant.now(), Instant.now(), 2L);
    }

    private KnowledgeDocument document() {
        return new KnowledgeDocument(101L, 1L, 10L, null, "dataset-1", null, "WAITING", 1L,
                null, Instant.now(), Instant.now(), 0L);
    }

    private KnowledgeSyncJob job() {
        return new KnowledgeSyncJob(501L, 1L, 101L, 20L, HASH, "WAITING", 0, 5, null, null,
                null, null, null, null, 1L, "publish-1", "request-hash", null, null,
                Instant.now(), Instant.now(), 0L);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
