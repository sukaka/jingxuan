package com.whale.crm.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.fulfillment.api.dto.CreateReversalRequest;
import com.whale.crm.fulfillment.api.dto.PostRedemptionRequest;
import com.whale.crm.fulfillment.api.dto.RedemptionDetail;
import com.whale.crm.fulfillment.api.dto.RedemptionLineRequest;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.application.FulfillmentSupport;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.fulfillment.application.ReversalApplicationService;
import com.whale.crm.fulfillment.domain.Appointment;
import com.whale.crm.fulfillment.domain.AppointmentHistory;
import com.whale.crm.fulfillment.domain.AppointmentItem;
import com.whale.crm.fulfillment.domain.FulfillmentRepository;
import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.InstitutionAccessSupport;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.settlement.application.SettlementApplicationService;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import com.whale.crm.trading.domain.LockedEntitlementBatch;
import com.whale.crm.trading.domain.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedemptionApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final CurrentUser OPERATOR_T2 = new CurrentUser(10L, 2L, "op2", "操作员", "INSTITUTION", null,
            Set.of("INSTITUTION_OPERATOR"), Set.of("redemption:post"), Set.of(1L));
    private static final CurrentUser MANAGER_T2 = new CurrentUser(2L, 2L, "mgr2", "管家", "EMPLOYEE", null,
            Set.of("MANAGER"), Set.of("redemption:reverse"), Set.of());

    private FulfillmentRepository repository;
    private FulfillmentSupport support;
    private FulfillmentTradingOperations tradingOperations;
    private SettlementApplicationService settlementService;
    private RedemptionApplicationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository = mock(FulfillmentRepository.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        InstitutionAccessSupport institutionAccess = mock(InstitutionAccessSupport.class);
        when(institutionAccess.canAccess(2L, OPERATOR_T2, 1L)).thenReturn(true);
        tradingOperations = mock(FulfillmentTradingOperations.class);
        settlementService = mock(SettlementApplicationService.class);
        support = new FulfillmentSupport(repository, scopeSupport, institutionAccess,
                mock(IdempotencyService.class), tradingOperations, new ObjectMapper());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RedemptionApplicationService(repository, support, scopeSupport, institutionAccess,
                tradingOperations, settlementService, mock(FileApplicationService.class),
                mock(OutboxService.class), mock(AuditService.class), txManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void requestReversalLocksRedemptionAndRejectsSettledRedemption() {
        Redemption redemption = new Redemption(50L, 30L, 100L, 1L, 2L, null, NOW.minusSeconds(60), "proof",
                null, "POSTED", "PENDING_VERIFICATION", "post-k", 10L, NOW, NOW, 0L);
        when(repository.findRedemption(2L, 50L)).thenReturn(Optional.of(redemption));
        when(repository.findRedemptionForUpdate(2L, 50L)).thenReturn(Optional.of(redemption));
        when(settlementService.hasSettlementLineForUpdate(2L, 50L)).thenReturn(true);

        assertThatThrownBy(() -> service.requestReversal(2L, MANAGER_T2, 50L,
                new CreateReversalRequest(0L, "已结算测试"), "rev-settled"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));

        InOrder order = inOrder(repository, settlementService);
        order.verify(repository).findRedemptionForUpdate(2L, 50L);
        order.verify(settlementService).hasSettlementLineForUpdate(2L, 50L);
        verify(repository, never()).insertReversal(any(Long.class), any());
    }

    @Test
    void postAndRequestReversalPassExplicitTenantId2EvenWhenSecurityContextCleared() {
        Appointment appointment = new Appointment(30L, "A000000000030", 100L, 1L, 2L, null,
                NOW.plusSeconds(3600), "remark", "SERVICE_DONE", 2L, NOW, 2L);
        AppointmentItem item = new AppointmentItem(40L, 30L, 70L, 101L, 1, NOW, 0L);
        Redemption createdRedemption = new Redemption(50L, 30L, 100L, 1L, 2L, null, NOW.minusSeconds(60), "proof",
                null, "POSTED", "PENDING_VERIFICATION", "post-t2", 10L, NOW, NOW, 0L);
        RedemptionLine persistedLine = new RedemptionLine(60L, 50L, 40L, 70L, 101L, 1, NOW);
        Entitlement entitlement = new Entitlement(70L, 100L, 20L, 101L, "水光", 2, 2, 0,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AVAILABLE", NOW, 0L);
        Order order = new Order(10L, "J10", 100L, null, 2L, 2L, null, "CRM", null, "REGULAR",
                null, null, "PAID", "PLATFORM", 1000L, 0L, 1000L, 1000L, 0L, NOW, NOW, NOW, 0L);
        LockedEntitlementBatch locked = new LockedEntitlementBatch(
                Map.of(10L, order),
                Map.of(70L, new FulfillmentEntitlementView(entitlement, 10L, List.of(1L), "CONFIGURED")));

        when(repository.findAppointment(2L, 30L)).thenReturn(Optional.of(appointment));
        when(repository.findAppointmentForUpdate(2L, 30L)).thenReturn(Optional.of(appointment));
        when(repository.findAppointmentHistory(2L, 30L)).thenReturn(List.of(
                new AppointmentHistory(1L, 30L, "CONFIRMED", "CHECKED_IN", 10L, "arrived", NOW.minusSeconds(300))));
        when(repository.findAppointmentItems(2L, 30L)).thenReturn(List.of(item));
        when(repository.findPostedLinesByAppointment(2L, 30L)).thenReturn(List.of());
        when(tradingOperations.lockRedeemables(eq(2L), anyList())).thenReturn(locked);
        when(repository.findRedemptionByKey(2L, "post-t2")).thenReturn(Optional.of(createdRedemption));
        when(repository.findRedemptionLines(2L, 50L)).thenReturn(List.of(persistedLine));
        when(repository.updateAppointmentStatus(2L, 30L, 2L, "SERVICE_DONE", "REDEEMED")).thenReturn(1);
        when(repository.findRedemption(2L, 50L)).thenReturn(Optional.of(createdRedemption));

        RedemptionDetail posted = service.post(2L, OPERATOR_T2, 30L,
                new PostRedemptionRequest(2L, NOW.minusSeconds(60), "proof", null,
                        List.of(new RedemptionLineRequest(70L, 1))), "post-t2");
        assertThat(posted.redemption().id()).isEqualTo(50L);

        InOrder postOrder = inOrder(repository, tradingOperations);
        postOrder.verify(repository).findAppointmentForUpdate(2L, 30L);
        postOrder.verify(tradingOperations).lockRedeemables(eq(2L), anyList());
        postOrder.verify(repository).insertRedemption(eq(2L), any());
        postOrder.verify(repository).insertRedemptionLine(eq(2L), any());
        postOrder.verify(tradingOperations).appendRedemptions(eq(2L), eq(10L), eq(locked), anyList());
        verify(repository).insertRedemptionHistory(eq(2L), any());
        verify(repository).insertAppointmentHistory(eq(2L), any());

        RedemptionReversal reversal = new RedemptionReversal(80L, 50L, 100L, 1L, 2L, null, "REQUESTED", "reason",
                2L, NOW, null, null, null, "rev-t2", null, 50L, NOW, 0L);
        when(repository.findRedemptionForUpdate(2L, 50L)).thenReturn(Optional.of(createdRedemption));
        when(settlementService.hasSettlementLineForUpdate(2L, 50L)).thenReturn(false);
        when(repository.findActiveReversalByRedemption(2L, 50L)).thenReturn(Optional.empty());
        when(repository.findReversalByRequestKey(2L, "rev-t2")).thenReturn(Optional.of(reversal));
        when(repository.findReversal(2L, 80L)).thenReturn(Optional.of(reversal));

        ReversalDetail requested = service.requestReversal(2L, MANAGER_T2, 50L,
                new CreateReversalRequest(0L, "reason"), "rev-t2");
        assertThat(requested.reversal().id()).isEqualTo(80L);
        verify(repository).insertReversal(eq(2L), any());
        verify(repository).insertReversalHistory(eq(2L), any());
    }
}
