package com.whale.crm.ai;

import com.whale.crm.ai.application.AiChatDelegationService;
import com.whale.crm.ai.application.AiChatDelegationWorker;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatDelegationWorkerTest {
    @Mock AiChatDelegationRepository repository;
    @Mock AiChatDelegationService service;
    @Mock PlatformTransactionManager transactionManager;
    @Mock AiChatDelegationRun expired;

    @Test
    void expiredSendingLeaseBecomesUnknownWithoutCallingSendAgain() {
        when(repository.findExpiredSending(any(), anyInt())).thenReturn(List.of(expired));
        when(expired.tenantId()).thenReturn(1L);
        when(expired.id()).thenReturn(11L);
        when(repository.findWorkTenantIds(any())).thenReturn(List.of());
        AiChatDelegationWorker worker = new AiChatDelegationWorker(repository, service, transactionManager,
                true, 10, 45000);

        worker.runOnce();

        verify(service).recoverUnknown(1L, 11L);
        verify(service, never()).send(anyLong(), anyLong(), any());
    }

    @Test
    void runtimeWorkerSwitchDefaultsToNoProcessing() {
        AiChatDelegationWorker worker = new AiChatDelegationWorker(repository, service, transactionManager,
                false, 10, 45000);

        worker.runOnce();

        verify(repository, never()).findExpiredSending(any(), anyInt());
        verify(repository, never()).findWorkTenantIds(any());
    }
}
