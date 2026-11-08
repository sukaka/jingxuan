package com.whale.crm.ai;

import com.whale.crm.ai.application.DifyKnowledgeConfigService;
import com.whale.crm.ai.application.KnowledgeManagementService;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeManagementServiceTest {
    @Mock KnowledgeRepository repository;
    @Mock DifyKnowledgeConfigService configService;
    @Mock KnowledgeWorkerClient workerClient;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;
    @Mock PlatformTransactionManager transactionManager;

    private KnowledgeManagementService service;
    private final CurrentUser admin = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("ai:knowledge:read", "ai:knowledge:publish"), Set.of());

    @BeforeEach
    void setUp() {
        service = new KnowledgeManagementService(repository, configService, workerClient, idempotencyService,
                auditService, transactionManager);
    }

    @Test
    void failedJobCanRetryWithResetAttemptsAndAudit() {
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("FAILED")), Optional.of(job("PENDING")));
        when(repository.retryJob(1L, 501L, 3L)).thenReturn(1);

        var response = service.retry(admin, 501L, "retry-1");

        assertThat(response.status()).isEqualTo("PENDING");
        verify(repository).retryJob(1L, 501L, 3L);
        verify(auditService).record(eq(1L), eq(1L), eq("KNOWLEDGE_SYNC_RETRY"), eq("KNOWLEDGE_SYNC_JOB"),
                eq("501"), any(), any(), any());
    }

    @Test
    void succeededJobCannotRetry() {
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("SUCCEEDED")));

        assertThatThrownBy(() -> service.retry(admin, 501L, "retry-success"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("FAILED");
        verify(repository, never()).retryJob(anyLong(), anyLong(), anyLong());
    }

    @Test
    void activeDocumentDisablesRemoteBeforeUpdatingCrm() {
        KnowledgeDocument document = document("doc-1", "ACTIVE");
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document),
                Optional.of(document("doc-1", "DISABLED")));
        KnowledgeWorkerClient.DatasetProviderConfig config = new KnowledgeWorkerClient.DatasetProviderConfig(
                "mock", "", "", "mock-dataset", 30);
        when(configService.datasetWorkerConfig(1L)).thenReturn(config);
        when(repository.disableDocument(1L, 101L, 4L)).thenReturn(1);

        var response = service.disable(admin, 101L, "disable-1");

        InOrder order = inOrder(workerClient, repository);
        order.verify(workerClient).disableDocument(config, "doc-1", "knowledge-disable-101");
        order.verify(repository).disableDocument(1L, 101L, 4L);
        assertThat(response.status()).isEqualTo("DISABLED");
    }

    @Test
    void waitingDocumentWithoutRemoteIdCanDisableDirectly() {
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(document(null, "WAITING")),
                Optional.of(document(null, "DISABLED")));
        when(repository.disableDocument(1L, 101L, 4L)).thenReturn(1);

        service.disable(admin, 101L, "disable-waiting");

        verify(workerClient, never()).disableDocument(any(), anyString(), anyString());
        verify(repository).disableDocument(1L, 101L, 4L);
    }

    private KnowledgeDocument document(String difyDocumentId, String status) {
        return new KnowledgeDocument(101L, 1L, 10L, 20L, "mock-dataset", difyDocumentId, status, 1L,
                Instant.now(), Instant.now(), Instant.now(), 4L);
    }

    private KnowledgeSyncJob job(String status) {
        return new KnowledgeSyncJob(501L, 1L, 101L, 20L, "a".repeat(64), status, 3, 5, null,
                null, null, null, null, null, 1L, "publish-1", "hash", null, null,
                Instant.now(), Instant.now(), 3L);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
