package com.whale.crm.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.api.dto.ReviewReversalRequest;
import com.whale.crm.fulfillment.application.FulfillmentSupport;
import com.whale.crm.fulfillment.application.ReversalApplicationService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReversalApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final CurrentUser FINANCE_T2 = new CurrentUser(5L, 2L, "fin2", "财务", "EMPLOYEE", null,
            Set.of("FINANCE"), Set.of("redemption:approve"), Set.of());

    private FulfillmentRepository repository;
    private FulfillmentTradingOperations tradingOperations;
    private SettlementApplicationService settlementService;
    private ReversalApplicationService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        repository = mock(FulfillmentRepository.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        tradingOperations = mock(FulfillmentTradingOperations.class);
        settlementService = mock(SettlementApplicationService.class);
        FulfillmentSupport support = new FulfillmentSupport(repository, scopeSupport,
                mock(InstitutionAccessSupport.class), mock(IdempotencyService.class),
                tradingOperations, new ObjectMapper());
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ReversalApplicationService(repository, support, scopeSupport, tradingOperations,
                settlementService, mock(OutboxService.class), mock(AuditService.class), txManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveLocksRedemptionBeforeSettlementCheckAndRejectsSettledRedemption() {
        RedemptionReversal before = reversal("REQUESTED", 50L);
        Redemption redemption = new Redemption(50L, 30L, 100L, 1L, 2L, null, NOW, "proof", null,
                "POSTED", "PENDING_VERIFICATION", "post-k", 10L, NOW, NOW, 0L);
        when(repository.findReversal(2L, 80L)).thenReturn(Optional.of(before));
        when(repository.findReversalForUpdate(2L, 80L)).thenReturn(Optional.of(before));
        when(repository.findRedemptionForUpdate(2L, 50L)).thenReturn(Optional.of(redemption));
        when(settlementService.hasSettlementLineForUpdate(2L, 50L)).thenReturn(true);

        assertThatThrownBy(() -> service.review(2L, FINANCE_T2, 80L, "approve",
                new ReviewReversalRequest(0L, "approve"), "rev-app-settled"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));

        InOrder order = inOrder(repository, settlementService);
        order.verify(repository).findReversalForUpdate(2L, 80L);
        order.verify(repository).findRedemptionForUpdate(2L, 50L);
        order.verify(settlementService).hasSettlementLineForUpdate(2L, 50L);
        verify(tradingOperations, never()).lockReversibles(anyLong(), anyList());
        verify(tradingOperations, never()).appendReversals(anyLong(), anyLong(), any(), anyList());
    }

    @Test
    void rejectStillSucceedsEvenIfRedemptionIsSettledAndPassesExplicitTenantId2() {
        RedemptionReversal before = reversal("REQUESTED", 50L);
        RedemptionReversal rejected = reversal("REJECTED", null);
        when(repository.findReversal(2L, 80L)).thenReturn(Optional.of(before), Optional.of(rejected));
        when(repository.findReversalForUpdate(2L, 80L)).thenReturn(Optional.of(before));
        when(settlementService.hasSettlementLineForUpdate(2L, 50L)).thenReturn(true);
        when(repository.updateReversalStatus(eq(2L), eq(80L), eq(0L), eq("REQUESTED"), eq("REJECTED"),
                eq(5L), any(), eq("reject"), eq("rev-rej"), eq(null))).thenReturn(1);

        ReversalDetail detail = service.review(2L, FINANCE_T2, 80L, "reject",
                new ReviewReversalRequest(0L, "reject"), "rev-rej");
        assertThat(detail.reversal().status()).isEqualTo("REJECTED");
        verify(repository, never()).findRedemptionForUpdate(anyLong(), anyLong());
        verify(settlementService, never()).hasSettlementLineForUpdate(anyLong(), anyLong());
        verify(repository).insertReversalHistory(eq(2L), any());
    }

    @Test
    void approveFollowsGlobalLockOrderAndPassesExplicitTenantId2() {
        RedemptionReversal before = reversal("REQUESTED", 50L);
        RedemptionReversal approved = reversal("APPROVED", 50L);
        Redemption redemption = new Redemption(50L, 30L, 100L, 1L, 2L, null, NOW, "proof", null,
                "POSTED", "PENDING_VERIFICATION", "post-k", 10L, NOW, NOW, 0L);
        RedemptionLine line = new RedemptionLine(60L, 50L, 40L, 70L, 101L, 1, NOW);
        Entitlement entitlement = new Entitlement(70L, 100L, 20L, 101L, "水光", 2, 1, 0,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "AVAILABLE", NOW, 0L);
        Order order = new Order(10L, "J10", 100L, null, 2L, 2L, null, "CRM", null, "REGULAR",
                null, null, "PARTIALLY_REDEEMED", "PLATFORM", 1000L, 0L, 1000L, 1000L, 0L, NOW, NOW, NOW, 0L);
        LockedEntitlementBatch locked = new LockedEntitlementBatch(
                Map.of(10L, order),
                Map.of(70L, new FulfillmentEntitlementView(entitlement, 10L, List.of(1L), "CONFIGURED")));

        when(repository.findReversal(2L, 80L)).thenReturn(Optional.of(before), Optional.of(approved));
        when(repository.findReversalForUpdate(2L, 80L)).thenReturn(Optional.of(before));
        when(repository.findRedemptionForUpdate(2L, 50L)).thenReturn(Optional.of(redemption));
        when(settlementService.hasSettlementLineForUpdate(2L, 50L)).thenReturn(false);
        when(repository.findRedemptionLines(2L, 50L)).thenReturn(List.of(line));
        when(tradingOperations.lockReversibles(eq(2L), anyList())).thenReturn(locked);
        when(repository.updateReversalStatus(eq(2L), eq(80L), eq(0L), eq("REQUESTED"), eq("APPROVED"),
                eq(5L), any(), eq("ok"), eq("rev-app-t2"), eq(50L))).thenReturn(1);

        ReversalDetail detail = service.review(2L, FINANCE_T2, 80L, "approve",
                new ReviewReversalRequest(0L, "ok"), "rev-app-t2");
        assertThat(detail.reversal().status()).isEqualTo("APPROVED");

        InOrder lockOrder = inOrder(repository, settlementService, tradingOperations);
        lockOrder.verify(repository).findReversalForUpdate(2L, 80L);
        lockOrder.verify(repository).findRedemptionForUpdate(2L, 50L);
        lockOrder.verify(settlementService).hasSettlementLineForUpdate(2L, 50L);
        lockOrder.verify(tradingOperations).lockReversibles(eq(2L), anyList());
        lockOrder.verify(tradingOperations).appendReversals(eq(2L), eq(5L), eq(locked), anyList());
        lockOrder.verify(repository).insertReversalHistory(eq(2L), any());
    }

    private RedemptionReversal reversal(String status, Long activeRedemptionId) {
        return new RedemptionReversal(80L, 50L, 100L, 1L, 2L, null, status, "reason",
                2L, NOW, null, null, null, "rev-req", null, activeRedemptionId, NOW, 0L);
    }
}
