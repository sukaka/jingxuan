package com.whale.crm.ai;

import com.whale.crm.ai.application.AiJobApplicationService;
import com.whale.crm.ai.application.AiJobWorker;
import com.whale.crm.ai.application.AiProviderConfigService;
import com.whale.crm.ai.domain.AiJob;
import com.whale.crm.ai.domain.AiJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiJobWorkerTest {
    @Mock AiJobRepository repository;
    @Mock AiJobApplicationService jobService;
    @Mock AiProviderConfigService providerConfigService;
    @Mock PlatformTransactionManager transactionManager;
    @Mock AiJob candidate;

    @Test
    void scheduledUsesWebEnabledEvenWhenEnvironmentDefaultIsFalse() {
        when(repository.claimTenantIds(any())).thenReturn(List.of(1L));
        when(providerConfigService.workerEnabled(1L)).thenReturn(true);
        when(repository.claimable(eq(1L), any(), eq(10))).thenReturn(List.of(candidate));
        when(candidate.id()).thenReturn(1L);
        when(candidate.version()).thenReturn(0L);
        when(repository.claim(eq(1L), eq(1L), eq(0L), any(), any(), any())).thenReturn(1);
        AiJobWorker worker = worker();

        worker.scheduled();

        verify(jobService).execute(1L, 1L);
    }

    @Test
    void scheduledDoesNotClaimTenantDisabledOnWeb() {
        when(repository.claimTenantIds(any())).thenReturn(List.of(1L));
        when(providerConfigService.workerEnabled(1L)).thenReturn(false);
        AiJobWorker worker = worker();

        worker.scheduled();
        verify(repository, never()).claimable(anyLong(), any(), anyInt());
        verify(jobService, never()).execute(anyLong(), anyLong());
    }

    private AiJobWorker worker() {
        return new AiJobWorker(repository, jobService, providerConfigService, transactionManager, 10, 45000);
    }
}
