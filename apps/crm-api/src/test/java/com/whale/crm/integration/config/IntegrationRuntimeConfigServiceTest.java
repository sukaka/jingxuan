package com.whale.crm.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.config.api.dto.IntegrationRuntimeConfigRequest;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.config.domain.IntegrationRuntimeConfig;
import com.whale.crm.integration.config.domain.IntegrationRuntimeConfigRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationRuntimeConfigServiceTest {
    @Mock IntegrationRuntimeConfigRepository repository;
    @Mock IdempotencyService idempotencyService;
    @Mock AuditService auditService;

    private IntegrationRuntimeConfigService service;
    private final CurrentUser admin = new CurrentUser(1, 1, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("integration:runtime:manage"), Set.of());

    @BeforeEach
    void setUp() {
        service = new IntegrationRuntimeConfigService(repository, idempotencyService, auditService,
                new ObjectMapper(), transactionManager(), false, false, false);
    }

    @Test
    void environmentIsDefaultAndDatabaseOverridesOnlyCurrentTenant() {
        when(repository.find(1L)).thenReturn(Optional.empty());
        when(repository.find(2L)).thenReturn(Optional.of(new IntegrationRuntimeConfig(1L, 2L,
                true, true, false, 3L)));

        assertThat(service.get(1L).source()).isEqualTo("ENVIRONMENT");
        assertThat(service.youzanRealEnabled(1L)).isFalse();
        assertThat(service.youzanRealEnabled(2L)).isTrue();
        assertThat(service.wecomArchiveRealEnabled(2L)).isTrue();
        assertThat(service.wecomOfficialEnabled(2L)).isFalse();
        assertThat(service.get(2L).source()).isEqualTo("DATABASE");
    }

    @Test
    void savePersistsAuditsAndRejectsStaleVersion() {
        IntegrationRuntimeConfig saved = new IntegrationRuntimeConfig(1L, 1L, true, false, true, 0L);
        when(repository.findForUpdate(1L)).thenReturn(Optional.empty());
        when(repository.find(1L)).thenReturn(Optional.of(saved));

        var response = service.save(admin, new IntegrationRuntimeConfigRequest(true, false, true, 0L), "save-key");

        assertThat(response.youzanRealEnabled()).isTrue();
        assertThat(response.wecomOfficialEnabled()).isTrue();
        verify(repository).insert(eq(1L), any());
        verify(idempotencyService).recordSuccess(eq(1L), eq("integration.runtime.config.save"), eq("save-key"),
                eq("INTEGRATION_RUNTIME_CONFIG"), eq("1"), any());
        verify(auditService).record(eq(1L), eq(1L), eq("INTEGRATION_RUNTIME_CONFIG_SAVE"),
                eq("INTEGRATION_RUNTIME_CONFIG"), eq("1"), any(), any(), any());

        assertThatThrownBy(() -> service.save(admin,
                new IntegrationRuntimeConfigRequest(false, false, false, 1L), "stale-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已变化");
    }

    @Test
    void idempotentReplayDoesNotWriteAgain() {
        when(idempotencyService.lookup(eq(1L), eq("integration.runtime.config.save"), eq("replay-key"), any()))
                .thenReturn(Optional.of(new IdempotencyRecord()));
        when(repository.find(1L)).thenReturn(Optional.of(new IntegrationRuntimeConfig(1L, 1L,
                true, false, true, 2L)));

        var response = service.save(admin, new IntegrationRuntimeConfigRequest(true, false, true, 2L), "replay-key");

        assertThat(response.version()).isEqualTo(2L);
        verify(repository, never()).insert(any(Long.class), any());
        verify(repository, never()).findForUpdate(anyLong());
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }
}
