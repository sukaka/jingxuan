package com.whale.crm.exporting;

import com.whale.crm.exporting.application.ExportExecutionService;
import com.whale.crm.exporting.application.ExportWorker;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportWorkerTest {
    @Mock ExportJobRepository repository;
    @Mock ExportExecutionService executionService;
    @Mock PlatformTransactionManager transactionManager;

    @Test
    void claimsPendingAndExpiredRunningJobsFromRepositoryRecoveryQueue() {
        ExportJob pending = job(1L, ExportStatus.PENDING);
        ExportJob expired = job(2L, ExportStatus.RUNNING);
        when(repository.claimTenantIds(any())).thenReturn(List.of(1L));
        when(repository.claimable(eq(1L), any(), eq(5))).thenReturn(List.of(pending, expired));
        when(repository.claim(anyLong(), anyLong(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        ExportWorker worker = new ExportWorker(repository, executionService, transactionManager, true, 5, 60);

        assertThat(worker.runOnce()).isEqualTo(2);

        verify(executionService).execute(eq(1L), eq(1L), anyString());
        verify(executionService).execute(eq(1L), eq(2L), anyString());
    }

    @Test
    void disabledSchedulerDoesNotTouchQueue() {
        ExportWorker worker = new ExportWorker(repository, executionService, transactionManager, false, 5, 60);

        worker.scheduled();

        verify(repository, never()).claimTenantIds(any());
    }

    private ExportJob job(long id, ExportStatus status) {
        ExportJob job = new ExportJob();
        job.setId(id);
        job.setTenantId(1L);
        job.setStatus(status);
        job.setVersion(0L);
        return job;
    }
}
