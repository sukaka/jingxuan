package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.tier.api.dto.CustomerTierTrialRequest;
import com.whale.crm.customer.tier.domain.CustomerTierLevel;
import com.whale.crm.customer.tier.domain.CustomerTierMetricSnapshot;
import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.CustomerTierRepository;
import com.whale.crm.customer.tier.domain.CustomerTierRule;
import com.whale.crm.integration.wecom.adapter.DisabledWecomAdapter;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.adapter.WecomAdapter;
import com.whale.crm.integration.wecom.adapter.WecomAdapterRouter;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.application.WecomRecallService;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.media.application.WecomMediaRegistrationService;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerTierArchiveScopeTest {
    private static final Instant AS_OF = Instant.parse("2025-03-01T00:00:00Z");

    @Test
    void activeArchiveScopeReflectsOfficialMockAndDisabledAdapters() {
        WecomAdapterRouter router = mock(WecomAdapterRouter.class);
        WecomAdapter official = mock(WecomAdapter.class);
        when(official.name()).thenReturn("OFFICIAL_SDK");
        when(router.adapter(1L)).thenReturn(official);
        when(router.adapter(2L)).thenReturn(new MockWecomAdapter());
        when(router.adapter(3L)).thenReturn(new DisabledWecomAdapter());

        WecomArchiveService service = new WecomArchiveService(
                mock(WecomRepository.class), router, mock(AesGcmCipher.class),
                mock(ObjectProvider.class), mock(OutboxService.class), mock(AuditService.class),
                new ObjectMapper(), mock(CustomerScopeSupport.class),
                mock(WecomMediaRegistrationService.class), mock(WecomRecallService.class),
                mock(WecomRoomService.class), mock(PlatformTransactionManager.class));

        assertThat(service.activeArchiveScope(1L)).isEqualTo(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE);
        assertThat(service.activeArchiveScope(2L)).isEqualTo(WecomArchiveService.MOCK_ARCHIVE_SCOPE);
        assertThat(service.activeArchiveScope(3L)).isNull();
    }

    @Test
    void recalculationPassesActiveArchiveScopeToRepository() {
        CustomerTierRepository repository = mock(CustomerTierRepository.class);
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        TierEvaluationService evaluationService = new TierEvaluationService();
        CustomerTierConfigMapper configMapper = new CustomerTierConfigMapper(repository, new ObjectMapper());
        WecomArchiveService wecomArchiveService = mock(WecomArchiveService.class);

        Customer customer = new Customer();
        customer.setId(42L);
        customer.setTenantId(1L);
        customer.setOwnerStaffId(2L);
        customer.setTierCode("D");
        customer.setVersion(0L);
        customer.setDeleted(false);

        CustomerTierRule rule = new CustomerTierRule(10L, 1L, "RULE_1", 1, "默认规则", "UTC", "ACTIVE", "[]",
                1L, Instant.now(), null, Instant.now(), Instant.now(), 0L);

        when(customerRepository.findByIdForUpdate(1L, 42L)).thenReturn(Optional.of(customer));
        when(repository.findSnapshotByKey(1L, "recalc-official")).thenReturn(Optional.empty());
        when(repository.findActiveRuleForUpdate(1L)).thenReturn(Optional.of(rule));
        when(wecomArchiveService.activeArchiveScope(1L)).thenReturn(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE);
        when(repository.loadMetrics(eq(1L), eq(42L), any(), any(), eq(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE)))
                .thenReturn(new CustomerTierMetrics(0, 0, 0, 0, 1, 2, true));
        when(repository.findLevels(1L, 10L)).thenReturn(defaultLevels());
        when(repository.insertSnapshot(any())).thenAnswer(invocation -> {
            CustomerTierMetricSnapshot s = invocation.getArgument(0);
            return new CustomerTierMetricSnapshot(99L, s.tenantId(), s.customerId(), s.ruleId(),
                    s.recalculationKey(), s.sourceTrigger(), s.asOf(), s.windowStartInclusive(),
                    s.windowEndExclusive(), s.zoneId(), s.paymentAmountFen(), s.paidAmountFen(),
                    s.refundAmountFen(), s.orderCount(), s.manualFollowupCount(), s.archivedMessageCount(),
                    s.communicationCount(), s.communicationComplete(), s.actualConsumptionFen(), s.score(),
                    s.suggestedTierCode(), Instant.now());
        });
        when(repository.findPendingForUpdate(1L, 42L)).thenReturn(Optional.empty());

        CustomerTierRecalculationService service = new CustomerTierRecalculationService(
                repository, customerRepository, evaluationService, configMapper,
                mock(NotificationRequestService.class), mock(UserRepository.class),
                mock(AuditService.class), wecomArchiveService);

        var response = service.recalculate(1L, 42L, AS_OF, "MANUAL", "recalc-official", 1L);

        assertThat(response.communicationComplete()).isTrue();
        assertThat(response.communicationCount()).isEqualTo(3L);
        verify(repository).loadMetrics(eq(1L), eq(42L), any(), any(), eq(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE));
    }

    @Test
    void ruleTrialPassesActiveArchiveScopeToRepository() {
        CustomerTierRepository repository = mock(CustomerTierRepository.class);
        TierEvaluationService evaluationService = new TierEvaluationService();
        CustomerTierConfigMapper configMapper = new CustomerTierConfigMapper(repository, new ObjectMapper());
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        WecomArchiveService wecomArchiveService = mock(WecomArchiveService.class);

        CustomerTierRule rule = new CustomerTierRule(10L, 1L, "RULE_1", 1, "默认规则", "UTC", "ACTIVE", "[]",
                1L, Instant.now(), null, Instant.now(), Instant.now(), 0L);
        CurrentUser user = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("customer:tier:read"), Set.of());

        when(repository.findRule(1L, 10L)).thenReturn(Optional.of(rule));
        when(wecomArchiveService.activeArchiveScope(1L)).thenReturn(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE);
        when(repository.loadMetrics(eq(1L), eq(42L), any(), any(), eq(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE)))
                .thenReturn(new CustomerTierMetrics(0, 0, 0, 0, 1, 0, false));
        when(repository.findLevels(1L, 10L)).thenReturn(defaultLevels());

        CustomerTierRuleService ruleService = new CustomerTierRuleService(
                repository, evaluationService, configMapper, scopeSupport,
                mock(IdempotencyService.class), mock(AuditService.class),
                new ObjectMapper(), wecomArchiveService);

        var response = ruleService.trial(1L, user, 10L, new CustomerTierTrialRequest(42L, AS_OF));

        assertThat(response.communicationComplete()).isFalse();
        assertThat(response.communicationCount()).isNull();
        verify(repository).loadMetrics(eq(1L), eq(42L), any(), any(), eq(WecomArchiveService.OFFICIAL_ARCHIVE_SCOPE));
    }

    private List<CustomerTierLevel> defaultLevels() {
        return List.of(
                new CustomerTierLevel(1L, 1L, 10L, "S", "核心", 1, java.math.BigDecimal.ZERO, 1_000_000L),
                new CustomerTierLevel(2L, 1L, 10L, "A", "重要", 2, java.math.BigDecimal.ZERO, 800_001L),
                new CustomerTierLevel(3L, 1L, 10L, "B", "成长", 3, java.math.BigDecimal.ZERO, 1_000L),
                new CustomerTierLevel(4L, 1L, 10L, "C", "普通", 4, java.math.BigDecimal.ZERO, 1L),
                new CustomerTierLevel(5L, 1L, 10L, "D", "培育", 5, java.math.BigDecimal.ZERO, 0L));
    }
}
