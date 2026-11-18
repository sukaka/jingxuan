package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.api.dto.AiChatDelegationCustomerControlRequest;
import com.whale.crm.ai.api.dto.UpdateAiChatDelegationPolicyRequest;
import com.whale.crm.ai.application.AiChatDelegationSettingsService;
import com.whale.crm.ai.application.AiChatSendFenceService;
import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomExternalContactSenderRouter;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatDelegationSettingsServiceTest {
    @Mock AiChatDelegationRepository repository;
    @Mock CustomerScopeSupport scopeSupport;
    @Mock CustomerRepository customerRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock IdempotencyService idempotencyService;
    @Mock WecomExternalContactSenderRouter sender;
    @Mock IntegrationRuntimeConfigService runtimeConfigService;
    @Mock AiChatSendFenceService sendFenceService;
    private AiChatDelegationSettingsService service;
    private final CurrentUser manager = new CurrentUser(8, 1, "manager", "管家", "EMPLOYEE", 3L,
            Set.of("MANAGER"), Set.of("ai:read", "ai:write"), Set.of());

    @BeforeEach
    void setUp() {
        service = new AiChatDelegationSettingsService(repository, scopeSupport, customerRepository, userRepository,
                auditService, idempotencyService, new ObjectMapper().findAndRegisterModules(), sender,
                properties(true), runtimeConfigService, sendFenceService, true, true, true);
    }

    @Test
    void stalePolicyVersionCannotOverwriteAnotherAdministratorsChange() {
        when(repository.findPolicy(1L)).thenReturn(Optional.of(policy(4)));
        when(idempotencyService.lookup(eq(1L), anyString(), eq("stale-key"), anyString()))
                .thenReturn(Optional.empty());
        UpdateAiChatDelegationPolicyRequest request = new UpdateAiChatDelegationPolicyRequest(true, 60, 10,
                "09:00", "21:00", "Asia/Shanghai", List.of(), List.of(), 3L, "测试旧版本");

        assertThatThrownBy(() -> service.updatePolicy(manager, request, "stale-key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本");

        verify(repository, never()).updatePolicy(any(), anyLong(), anyLong());
        verify(repository, never()).replaceScopes(anyLong(), any());
        verify(auditService, never()).record(anyLong(), any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void takeoverLocksVisibleCustomerAndAppendsComplianceAttemptWithAudit() {
        AiChatDelegationCustomerControl saved = new AiChatDelegationCustomerControl(1L, 1L, 101L,
                "HUMAN_TAKEOVER", "客户要求人工服务", 8L, Instant.now(), 0L);
        when(repository.findCustomerControl(1L, 101L)).thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.findPendingForCustomerForUpdate(1L, 101L)).thenReturn(List.of(pendingRun()));
        when(idempotencyService.lookup(eq(1L), anyString(), eq("takeover-key"), anyString()))
                .thenReturn(Optional.empty());

        var response = service.setCustomerControl(manager, 101L,
                new AiChatDelegationCustomerControlRequest("HUMAN_TAKEOVER", 0L, "客户要求人工服务"),
                "takeover-key");

        assertThat(response.mode()).isEqualTo("HUMAN_TAKEOVER");
        verify(scopeSupport).requireVisibleForUpdate(1L, 101L, manager);
        verify(repository).insertCustomerControl(1L, 101L, "HUMAN_TAKEOVER", "客户要求人工服务", 8L);
        ArgumentCaptor<AiChatDelegationAttempt> attempt = ArgumentCaptor.forClass(AiChatDelegationAttempt.class);
        verify(repository).insertAttempt(attempt.capture());
        assertThat(attempt.getValue().phase()).isEqualTo("COMPLIANCE");
        assertThat(attempt.getValue().result()).isEqualTo("SKIPPED");
        assertThat(attempt.getValue().errorMasked()).isEqualTo("HUMAN_TAKEOVER");
        verify(sendFenceService).blockCustomer(1L, 101L);
        verify(repository).blockPendingForCustomer(1L, 101L, "HUMAN_TAKEOVER");
        verify(idempotencyService).recordSuccess(eq(1L), anyString(), eq("takeover-key"),
                eq("AI_CHAT_DELEGATION_CUSTOMER"), eq("101"), anyString());
        verify(auditService).record(eq(1L), eq(8L), eq("AI_CHAT_DELEGATION_CUSTOMER_HUMAN_TAKEOVER"),
                eq("AI_CHAT_DELEGATION_CUSTOMER"), eq("101"), any(), any(), eq("客户要求人工服务"));
    }

    @Test
    void emergencyStopBlocksTenantFenceBeforeSuccessfulReturn() {
        AiChatDelegationPolicy before = policy(0);
        AiChatDelegationPolicy stopped = new AiChatDelegationPolicy(1L, false, true, 60, 10,
                before.allowedStart(), before.allowedEnd(), before.zoneId(), List.of(), 1, List.of());
        when(repository.findPolicy(1L)).thenReturn(Optional.of(before), Optional.of(stopped));
        when(repository.updatePolicy(any(), eq(8L), eq(0L))).thenReturn(1);
        when(idempotencyService.lookup(eq(1L), anyString(), eq("stop-key"), anyString()))
                .thenReturn(Optional.empty());

        service.emergencyStop(manager, new com.whale.crm.ai.api.dto.AiChatDelegationEmergencyStopRequest(
                true, 0L, "紧急停用"), "stop-key");

        verify(sendFenceService).blockTenant(1L);
        verify(auditService).record(eq(1L), eq(8L), eq("AI_CHAT_DELEGATION_EMERGENCY_STOP"),
                eq("AI_CHAT_DELEGATION_POLICY"), eq("1"), any(), any(), eq("紧急停用"));
    }

    @Test
    void statusRequiresTenantRuntimeOfficialSwitchAsWellAsStaticSwitches() {
        when(sender.mode()).thenReturn("OFFICIAL");
        when(runtimeConfigService.wecomOfficialEnabled(1L)).thenReturn(false, true);
        when(scopeSupport.scope(manager)).thenReturn(DataScope.all());
        when(scopeSupport.queryOf(DataScope.all(), manager))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("ALL", List.of(), 8));
        when(repository.countByStatus(1L, "ALL", List.of(), 8)).thenReturn(List.of());

        assertThat(service.status(manager).realOfficialEnabled()).isFalse();
        assertThat(service.status(manager).realOfficialEnabled()).isTrue();
    }

    private AiChatDelegationPolicy policy(long version) {
        return new AiChatDelegationPolicy(1L, false, false, 60, 10, LocalTime.of(9, 0),
                LocalTime.of(21, 0), "Asia/Shanghai", List.of(), version, List.of());
    }

    private AiChatDelegationRun pendingRun() {
        Instant now = Instant.parse("2026-01-01T01:00:00Z");
        return new AiChatDelegationRun(11L, 1L, 91L, 101L, 8L, 3L, "corp", "external", "conversation",
                "READY_TO_SEND", null, "cipher", "GENERAL", "[]", "mock", "mock-v1", null, null,
                null, 1, 0, now, null, null, now, now, 2L);
    }

    private WecomOfficialProperties properties(boolean enabled) {
        return new WecomOfficialProperties(enabled, "corp", "secret", "https://qyapi.weixin.qq.com",
                null, null, 100, 100, 100, 1);
    }
}
