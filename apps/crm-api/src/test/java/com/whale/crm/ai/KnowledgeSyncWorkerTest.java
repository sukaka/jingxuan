package com.whale.crm.ai;

import com.whale.crm.ai.application.KnowledgeSyncExecutionService;
import com.whale.crm.ai.application.KnowledgeSyncWorker;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSyncWorkerTest {
    @Test
    void claimsNewAndIndexingJobsWithDifferentOptimisticTransitions() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeSyncExecutionService executionService = mock(KnowledgeSyncExecutionService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        KnowledgeSyncWorker worker = new KnowledgeSyncWorker(repository, executionService, transactionManager,
                true, 10, 30_000L);
        KnowledgeSyncJob pending = job(501L, "PENDING", 0L);
        KnowledgeSyncJob indexing = job(502L, "INDEXING", 1L);
        when(repository.claimTenantIds(any())).thenReturn(List.of(1L));
        when(repository.claimable(eq(1L), any(), eq(10))).thenReturn(List.of(pending, indexing));
        when(repository.claimNew(eq(1L), eq(501L), eq(0L), anyString(), any(), any())).thenReturn(1);
        when(repository.claimIndexing(eq(1L), eq(502L), eq(1L), anyString(), any())).thenReturn(1);

        int processed = worker.runOnce();

        assertThat(processed).isEqualTo(2);
        verify(executionService).execute(eq(1L), eq(501L), anyString());
        verify(executionService).execute(eq(1L), eq(502L), anyString());
    }

    private KnowledgeSyncJob job(long id, String status, long version) {
        return new KnowledgeSyncJob(id, 1L, 101L, 20L, "a".repeat(64), status, 1, 5, null,
                "worker-test-owner", Instant.now().plusSeconds(30), null, null, null, 1L, "key", "hash",
                null, null, Instant.now(), Instant.now(), version);
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
