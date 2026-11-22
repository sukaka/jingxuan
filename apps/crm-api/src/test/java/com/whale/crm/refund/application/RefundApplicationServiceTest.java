package com.whale.crm.refund.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.refund.api.dto.CreateRefundItemRequest;
import com.whale.crm.refund.api.dto.CreateRefundRequest;
import com.whale.crm.refund.api.dto.ExecuteRefundRequest;
import com.whale.crm.refund.api.dto.ManualRefundSuccessRequest;
import com.whale.crm.refund.api.dto.RefundCallbackRequest;
import com.whale.crm.refund.api.dto.ReviewRefundRequest;
import com.whale.crm.refund.domain.InFlightEntitlementCount;
import com.whale.crm.refund.domain.Refund;
import com.whale.crm.refund.domain.RefundItem;
import com.whale.crm.refund.domain.RefundPayment;
import com.whale.crm.refund.domain.RefundRepository;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final CurrentUser MANAGER = new CurrentUser(2, 1, "manager1", "管家", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("refund:read", "refund:write"), Set.of());
    private static final CurrentUser FINANCE = new CurrentUser(5, 1, "finance1", "财务", "EMPLOYEE", null,
            Set.of("FINANCE"), Set.of("refund:read", "refund:approve", "refund:execute"), Set.of());

    private RefundRepository repository;
    private TradingRepository tradingRepository;
    private FulfillmentTradingOperations fulfillmentTradingOperations;
    private IdempotencyService idempotencyService;
    private RefundApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(RefundRepository.class);
        tradingRepository = mock(TradingRepository.class);
        fulfillmentTradingOperations = mock(FulfillmentTradingOperations.class);
        idempotencyService = mock(IdempotencyService.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RefundApplicationService(
                repository,
                tradingRepository,
                fulfillmentTradingOperations,
                mock(CustomerScopeSupport.class),
                idempotencyService,
                mock(FileApplicationService.class),
                mock(OutboxService.class),
                mock(AuditService.class),
                new ObjectMapper().findAndRegisterModules(),
                txManager,
                Clock.fixed(NOW, SHANGHAI));
        when(idempotencyService.lookup(anyLong(), anyString(), any(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    @Test
    void manualSucceedFollowsGlobalLockOrderProcessesSortedItemsAndPersistsEvidence() {
        Order orderBefore = order(10L, "PARTIALLY_REDEEMED", 1000L, 0L, 0L);
        Order orderAfter = order(10L, "PARTIALLY_REDEEMED", 1000L, 600L, 1L);
        Refund refund = refund(70L, 1L, 10L, 600L, 600L, "PROCESSING", 2L);
        RefundPayment payment = payment(80L, 1L, 70L, "BANK_TRANSFER", "EXT-70", 600L, "PENDING", 0L);
        Entitlement e100 = entitlement(100L, 101L, 3, 2, "AVAILABLE", LocalDate.of(2027, 12, 31));
        Entitlement e200 = entitlement(200L, 102L, 3, 2, "AVAILABLE", LocalDate.of(2027, 12, 31));
        List<RefundItem> unsortedItems = List.of(
                new RefundItem(2L, 1L, 70L, 200L, 1, 400L),
                new RefundItem(1L, 1L, 70L, 100L, 1, 200L));

        when(repository.findById(1L, 70L)).thenReturn(Optional.of(refund));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(orderBefore), Optional.of(orderAfter));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e100, e200));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(refund));
        when(repository.findPayments(1L, 70L)).thenReturn(List.of(payment));
        when(repository.findPaymentByExternal(1L, "BANK_TRANSFER", "EXT-70")).thenReturn(Optional.of(payment));
        when(repository.findPaymentForUpdate(1L, 80L)).thenReturn(Optional.of(payment));
        when(repository.findItems(1L, 70L)).thenReturn(unsortedItems);
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 500L, 500L, "{}", 0L),
                new OrderItem(102L, 10L, 2L, 2L, "S2", "N2", 1, 500L, 500L, "{}", 0L)));
        when(tradingRepository.updateEntitlementBalance(1L, 100L, 0L, 1, "AVAILABLE", 1)).thenReturn(1);
        when(tradingRepository.updateEntitlementBalance(1L, 200L, 0L, 1, "AVAILABLE", 1)).thenReturn(1);
        when(tradingRepository.sumLedgerForUpdate(1L, 100L)).thenReturn(1L);
        when(tradingRepository.sumLedgerForUpdate(1L, 200L)).thenReturn(1L);
        when(tradingRepository.addRefundedFen(1L, 10L, 0L, 600L)).thenReturn(1);
        when(repository.updatePaymentStatus(1L, 80L, 0L, "PENDING", "SUCCEEDED", NOW, "VOUCHER-REF", 999L))
                .thenReturn(1);
        when(repository.updateStatus(1L, 70L, 2L, "PROCESSING", "SUCCEEDED", null, 600L, null, null, NOW))
                .thenReturn(1);

        service.succeedManually(1L, FINANCE, 70L,
                new ManualRefundSuccessRequest("EXT-70", 600L, NOW, "VOUCHER-REF", 999L), "key-succ-1");

        InOrder lockOrder = inOrder(tradingRepository, repository, fulfillmentTradingOperations);
        lockOrder.verify(tradingRepository).findOrderForUpdate(1L, 10L);
        lockOrder.verify(tradingRepository).findEntitlementsByOrderIdsForUpdate(1L, List.of(10L));
        lockOrder.verify(repository).findForUpdate(1L, 70L);
        lockOrder.verify(repository).findPaymentForUpdate(1L, 80L);
        lockOrder.verify(tradingRepository).updateEntitlementBalance(1L, 100L, 0L, 1, "AVAILABLE", 1);
        lockOrder.verify(tradingRepository).sumLedgerForUpdate(1L, 100L);
        lockOrder.verify(tradingRepository).updateEntitlementBalance(1L, 200L, 0L, 1, "AVAILABLE", 1);
        lockOrder.verify(tradingRepository).sumLedgerForUpdate(1L, 200L);
        lockOrder.verify(tradingRepository).addRefundedFen(1L, 10L, 0L, 600L);
        lockOrder.verify(tradingRepository).findOrderForUpdate(1L, 10L);
        lockOrder.verify(fulfillmentTradingOperations).recalculateOrderStatusAfterRefund(1L, 5L, orderAfter, 70L);
        lockOrder.verify(repository).updatePaymentStatus(1L, 80L, 0L, "PENDING", "SUCCEEDED", NOW, "VOUCHER-REF", 999L);

        verify(tradingRepository, never()).sumLedger(anyLong(), anyLong());
        verify(tradingRepository, never()).findEntitlementForUpdate(anyLong(), anyLong());
    }

    @Test
    void callbackDerivesTenant2UsesActor0LAndPreservesProviderInDoFail() {
        Order order = order(20L, "PAID", 1000L, 0L, 0L);
        Refund refund = refund(71L, 2L, 20L, 500L, 500L, "PROCESSING", 2L);
        RefundPayment payment = payment(81L, 2L, 71L, "MOCK", "EXT-T2-FAIL", 500L, "PENDING", 0L);

        when(repository.findPaymentsByExternal("MOCK", "EXT-T2-FAIL")).thenReturn(List.of(payment));
        when(repository.findById(2L, 71L)).thenReturn(Optional.of(refund));
        when(tradingRepository.findOrderForUpdate(2L, 20L)).thenReturn(Optional.of(order));
        when(repository.findForUpdate(2L, 71L)).thenReturn(Optional.of(refund));
        when(repository.findPaymentByExternal(2L, "MOCK", "EXT-T2-FAIL")).thenReturn(Optional.of(payment));
        when(repository.findPaymentForUpdate(2L, 81L)).thenReturn(Optional.of(payment));
        when(repository.updatePaymentStatus(2L, 81L, 0L, "PENDING", "FAILED", null, null, null)).thenReturn(1);
        when(repository.updateStatus(2L, 71L, 2L, "PROCESSING", "FAILED", null, null, null, null, null))
                .thenReturn(1);

        service.handleCallback("MOCK",
                new RefundCallbackRequest("EXT-T2-FAIL", 500L, "FAILED", NOW, "   "), "cb-key-1");

        verify(idempotencyService, times(2))
                .lookup(eq(2L), eq("refund.provider_callback"), eq(0L), eq("cb-key-1"), anyString());
        verify(idempotencyService, never())
                .lookup(anyLong(), anyString(), isNull(), anyString(), anyString());
        verify(idempotencyService).recordSuccess(eq(2L), eq("refund.provider_callback"), eq(0L),
                eq("cb-key-1"), eq("REFUND"), eq("71"), anyString());
        verify(repository).updatePaymentStatus(2L, 81L, 0L, "PENDING", "FAILED", null, null, null);
    }

    @Test
    void callbackRejectsCrossTenantExternalRefundCollisionWith409() {
        RefundPayment t1 = payment(81L, 1L, 71L, "MOCK", "SHARED-EXT", 500L, "PENDING", 0L);
        RefundPayment t2 = payment(82L, 2L, 72L, "MOCK", "SHARED-EXT", 500L, "PENDING", 0L);
        when(repository.findPaymentsByExternal("MOCK", "SHARED-EXT")).thenReturn(List.of(t1, t2));

        assertThatThrownBy(() -> service.handleCallback("MOCK",
                new RefundCallbackRequest("SHARED-EXT", 500L, "SUCCEEDED", NOW, "OK"), "cb-collision"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void createBlocksWhenInFlightCashOrRevokeCountExceedsRemainingCapacity() {
        Order order = order(10L, "PAID", 1000L, 0L, 0L);
        Entitlement e100 = entitlement(100L, 101L, 3, 3, "AVAILABLE", LocalDate.of(2027, 12, 31));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(order));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e100));
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 1000L, 1000L, "{}", 0L)));
        when(repository.sumInFlightAmountByOrder(1L, 10L, null)).thenReturn(600L);

        assertThatThrownBy(() -> service.create(1L, MANAGER,
                new CreateRefundRequest(10L, 500L, "CUSTOMER_REQUEST", "over-cash",
                        List.of(new CreateRefundItemRequest(100L, 1, 500L))), "k-cash"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        when(repository.sumInFlightAmountByOrder(1L, 10L, null)).thenReturn(400L);
        when(repository.sumInFlightRevokeCountsByOrder(1L, 10L, null))
                .thenReturn(List.of(new InFlightEntitlementCount(100L, 2L)));

        assertThatThrownBy(() -> service.create(1L, MANAGER,
                new CreateRefundRequest(10L, 400L, "CUSTOMER_REQUEST", "over-count",
                        List.of(new CreateRefundItemRequest(100L, 2, 400L))), "k-count"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void executeBlocksApprovedAfterRedemptionAndBlocksFailedRetryWhenOtherRefundClaimedCapacity() {
        Order order = order(10L, "PARTIALLY_REDEEMED", 1000L, 0L, 0L);
        Entitlement e100 = entitlement(100L, 101L, 3, 1, "AVAILABLE", LocalDate.of(2027, 12, 31));
        Refund approvedRefund = refund(70L, 1L, 10L, 600L, 600L, "APPROVED", 1L);

        when(repository.findById(1L, 70L)).thenReturn(Optional.of(approvedRefund));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(order));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e100));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(approvedRefund));
        when(repository.sumInFlightAmountByOrder(1L, 10L, 70L)).thenReturn(0L);
        when(repository.findItems(1L, 70L)).thenReturn(List.of(new RefundItem(1L, 1L, 70L, 100L, 2, 600L)));
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 1000L, 1000L, "{}", 0L)));
        when(repository.sumInFlightRevokeCountsByOrder(1L, 10L, 70L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.execute(1L, FINANCE, 70L,
                new ExecuteRefundRequest(1L, "MOCK", "EXT-EXEC-1", 600L, "ev"), "k-exec-redeemed"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        Refund failedRefund = refund(71L, 1L, 10L, 600L, 600L, "FAILED", 3L);
        when(repository.findById(1L, 71L)).thenReturn(Optional.of(failedRefund));
        when(repository.findForUpdate(1L, 71L)).thenReturn(Optional.of(failedRefund));
        when(repository.sumInFlightAmountByOrder(1L, 10L, 71L)).thenReturn(500L);
        when(repository.findItems(1L, 71L)).thenReturn(List.of(new RefundItem(2L, 1L, 71L, 100L, 1, 600L)));

        assertThatThrownBy(() -> service.execute(1L, FINANCE, 71L,
                new ExecuteRefundRequest(3L, "MOCK", "EXT-RETRY-1", 600L, "ev"), "k-retry-blocked"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void reviewWithPartialApprovedFenProportionallyReallocatesKeepingZeroItemsAtZero() {
        Order order = order(10L, "PAID", 1000L, 0L, 0L);
        Refund requestedRefund = refund(70L, 1L, 10L, 1000L, 0L, "REQUESTED", 0L);
        Entitlement e100 = entitlement(100L, 101L, 1, 1, "AVAILABLE", LocalDate.of(2027, 12, 31));
        Entitlement e200 = entitlement(200L, 102L, 1, 1, "AVAILABLE", LocalDate.of(2027, 12, 31));
        Entitlement e300 = entitlement(300L, 103L, 1, 1, "AVAILABLE", LocalDate.of(2027, 12, 31));
        List<RefundItem> beforeItems = List.of(
                new RefundItem(1L, 1L, 70L, 100L, 1, 0L),
                new RefundItem(2L, 1L, 70L, 200L, 1, 333L),
                new RefundItem(3L, 1L, 70L, 300L, 1, 667L));
        List<RefundItem> reallocatedItems = List.of(
                new RefundItem(1L, 1L, 70L, 100L, 1, 0L),
                new RefundItem(2L, 1L, 70L, 200L, 1, 167L),
                new RefundItem(3L, 1L, 70L, 300L, 1, 333L));

        when(repository.findById(1L, 70L)).thenReturn(Optional.of(requestedRefund));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(order));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L)))
                .thenReturn(List.of(e100, e200, e300));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(requestedRefund));
        when(repository.sumInFlightAmountByOrder(1L, 10L, 70L)).thenReturn(0L);
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 0L, 0L, "{}", 0L),
                new OrderItem(102L, 10L, 2L, 2L, "S2", "N2", 1, 333L, 333L, "{}", 0L),
                new OrderItem(103L, 10L, 3L, 3L, "S3", "N3", 1, 667L, 667L, "{}", 0L)));
        when(repository.sumInFlightRevokeCountsByOrder(1L, 10L, 70L)).thenReturn(List.of());
        when(repository.findItems(1L, 70L)).thenReturn(beforeItems, reallocatedItems, reallocatedItems);
        when(repository.updateItemAllocatedFen(1L, 2L, 70L, 167L)).thenReturn(1);
        when(repository.updateItemAllocatedFen(1L, 3L, 70L, 333L)).thenReturn(1);
        when(repository.updateStatus(1L, 70L, 0L, "REQUESTED", "APPROVED", 500L, null, 5L, null, null))
                .thenReturn(1);

        service.review(1L, FINANCE, 70L, new ReviewRefundRequest(0L, "APPROVED", 500L, "partial"), "k-partial-rev");

        verify(repository, never()).updateItemAllocatedFen(eq(1L), eq(1L), eq(70L), anyLong());
        verify(repository).updateItemAllocatedFen(1L, 2L, 70L, 167L);
        verify(repository).updateItemAllocatedFen(1L, 3L, 70L, 333L);
        verify(repository).updateStatus(1L, 70L, 0L, "REQUESTED", "APPROVED", 500L, null, 5L, null, null);
    }

    @Test
    void succeedPreservesExpiredStatusAndPastValidToWithoutRevivingToAvailable() {
        Order orderBefore = order(10L, "PAID", 1000L, 0L, 0L);
        Order orderAfter = order(10L, "PAID", 1000L, 400L, 1L);
        Refund refund = refund(70L, 1L, 10L, 400L, 400L, "PROCESSING", 2L);
        RefundPayment payment = payment(80L, 1L, 70L, "BANK_TRANSFER", "EXT-EXP", 400L, "PENDING", 0L);
        Entitlement alreadyExpired = entitlement(100L, 101L, 3, 2, "EXPIRED", LocalDate.of(2026, 10, 1));
        Entitlement pastValidTo = entitlement(200L, 102L, 3, 2, "AVAILABLE", LocalDate.of(2026, 10, 19));

        when(repository.findById(1L, 70L)).thenReturn(Optional.of(refund));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(orderBefore), Optional.of(orderAfter));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L)))
                .thenReturn(List.of(alreadyExpired, pastValidTo));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(refund));
        when(repository.findPayments(1L, 70L)).thenReturn(List.of(payment));
        when(repository.findPaymentByExternal(1L, "BANK_TRANSFER", "EXT-EXP")).thenReturn(Optional.of(payment));
        when(repository.findPaymentForUpdate(1L, 80L)).thenReturn(Optional.of(payment));
        when(repository.findItems(1L, 70L)).thenReturn(List.of(
                new RefundItem(1L, 1L, 70L, 100L, 1, 200L),
                new RefundItem(2L, 1L, 70L, 200L, 1, 200L)));
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 500L, 500L, "{}", 0L),
                new OrderItem(102L, 10L, 2L, 2L, "S2", "N2", 1, 500L, 500L, "{}", 0L)));
        when(tradingRepository.updateEntitlementBalance(1L, 100L, 0L, 1, "EXPIRED", 1)).thenReturn(1);
        when(tradingRepository.updateEntitlementBalance(1L, 200L, 0L, 1, "EXPIRED", 1)).thenReturn(1);
        when(tradingRepository.sumLedgerForUpdate(1L, 100L)).thenReturn(1L);
        when(tradingRepository.sumLedgerForUpdate(1L, 200L)).thenReturn(1L);
        when(tradingRepository.addRefundedFen(1L, 10L, 0L, 400L)).thenReturn(1);
        when(repository.updatePaymentStatus(1L, 80L, 0L, "PENDING", "SUCCEEDED", NOW, "EV-EXP", 888L))
                .thenReturn(1);
        when(repository.updateStatus(1L, 70L, 2L, "PROCESSING", "SUCCEEDED", null, 400L, null, null, NOW))
                .thenReturn(1);

        service.succeedManually(1L, FINANCE, 70L,
                new ManualRefundSuccessRequest("EXT-EXP", 400L, NOW, "EV-EXP", 888L), "k-exp");

        verify(tradingRepository).updateEntitlementBalance(1L, 100L, 0L, 1, "EXPIRED", 1);
        verify(tradingRepository).updateEntitlementBalance(1L, 200L, 0L, 1, "EXPIRED", 1);
        verify(tradingRepository, never()).updateEntitlementBalance(anyLong(), anyLong(), anyLong(), anyInt(),
                eq("AVAILABLE"), anyInt());
    }

    @Test
    void crossProviderRetryWithSameExternalNoSucceedsManuallyAndRejectsStaleMockCallback() {
        Order orderBefore = order(10L, "PAID", 1000L, 0L, 0L);
        Order orderAfter = order(10L, "PAID", 1000L, 500L, 1L);
        Refund processingRefund = refund(70L, 1L, 10L, 500L, 500L, "PROCESSING", 4L);
        RefundPayment mockFailed = payment(80L, 1L, 70L, "MOCK", "EXT-CROSS", 500L, "FAILED", 1L);
        RefundPayment bankPending = payment(81L, 1L, 70L, "BANK_TRANSFER", "EXT-CROSS", 500L, "PENDING", 0L);
        Entitlement e100 = entitlement(100L, 101L, 3, 3, "AVAILABLE", LocalDate.of(2027, 12, 31));

        when(repository.findPaymentsByExternal("MOCK", "EXT-CROSS")).thenReturn(List.of(mockFailed));
        when(repository.findById(1L, 70L)).thenReturn(Optional.of(processingRefund));
        when(tradingRepository.findOrderForUpdate(1L, 10L))
                .thenReturn(Optional.of(orderBefore), Optional.of(orderBefore), Optional.of(orderAfter));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e100));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(processingRefund));
        when(repository.findPayments(1L, 70L)).thenReturn(List.of(mockFailed, bankPending));
        when(repository.findPaymentByExternal(1L, "MOCK", "EXT-CROSS")).thenReturn(Optional.of(mockFailed));
        when(repository.findPaymentForUpdate(1L, 80L)).thenReturn(Optional.of(mockFailed));

        // Stale MOCK callback must resolve MOCK (FAILED) -> 409 CONFLICT, never hijack BANK_TRANSFER (PENDING)
        assertThatThrownBy(() -> service.handleCallback("MOCK",
                new RefundCallbackRequest("EXT-CROSS", 500L, "SUCCEEDED", NOW, "STALE-MOCK"), "cb-stale-mock"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        when(repository.findPaymentByExternal(1L, "BANK_TRANSFER", "EXT-CROSS")).thenReturn(Optional.of(bankPending));
        when(repository.findPaymentForUpdate(1L, 81L)).thenReturn(Optional.of(bankPending));
        when(repository.findItems(1L, 70L)).thenReturn(List.of(new RefundItem(1L, 1L, 70L, 100L, 1, 500L)));
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 1000L, 1000L, "{}", 0L)));
        when(tradingRepository.updateEntitlementBalance(1L, 100L, 0L, 2, "AVAILABLE", 1)).thenReturn(1);
        when(tradingRepository.sumLedgerForUpdate(1L, 100L)).thenReturn(2L);
        when(tradingRepository.addRefundedFen(1L, 10L, 0L, 500L)).thenReturn(1);
        when(repository.updatePaymentStatus(1L, 81L, 0L, "PENDING", "SUCCEEDED", NOW, "BANK-OK", 777L))
                .thenReturn(1);
        when(repository.updateStatus(1L, 70L, 4L, "PROCESSING", "SUCCEEDED", null, 500L, null, null, NOW))
                .thenReturn(1);

        service.succeedManually(1L, FINANCE, 70L,
                new ManualRefundSuccessRequest("EXT-CROSS", 500L, NOW, "BANK-OK", 777L), "k-cross-manual");

        verify(repository).updatePaymentStatus(1L, 81L, 0L, "PENDING", "SUCCEEDED", NOW, "BANK-OK", 777L);

        // Idempotent replay with a new Idempotency-Key must find SUCCEEDED BANK_TRANSFER (not FAILED MOCK)
        Refund succeededRefund = refund(70L, 1L, 10L, 500L, 500L, "SUCCEEDED", 5L);
        RefundPayment bankSucceeded = payment(81L, 1L, 70L, "BANK_TRANSFER", "EXT-CROSS", 500L, "SUCCEEDED", 1L);
        when(repository.findById(1L, 70L)).thenReturn(Optional.of(succeededRefund));
        when(repository.findForUpdate(1L, 70L)).thenReturn(Optional.of(succeededRefund));
        when(repository.findPayments(1L, 70L)).thenReturn(List.of(mockFailed, bankSucceeded));
        when(repository.findPaymentByExternal(1L, "BANK_TRANSFER", "EXT-CROSS")).thenReturn(Optional.of(bankSucceeded));
        when(repository.findPaymentForUpdate(1L, 81L)).thenReturn(Optional.of(bankSucceeded));

        service.succeedManually(1L, FINANCE, 70L,
                new ManualRefundSuccessRequest("EXT-CROSS", 500L, NOW, "BANK-OK", 777L), "k-cross-manual-replay");
        verify(tradingRepository, times(1)).addRefundedFen(1L, 10L, 0L, 500L);
    }

    @Test
    void reverseCrossProviderRetryBankFailedToMockCallbackSucceeds() {
        Order orderBefore = order(10L, "PAID", 1000L, 0L, 0L);
        Order orderAfter = order(10L, "PAID", 1000L, 500L, 1L);
        Refund processingRefund = refund(72L, 1L, 10L, 500L, 500L, "PROCESSING", 4L);
        RefundPayment bankFailed = payment(82L, 1L, 72L, "BANK_TRANSFER", "EXT-REV", 500L, "FAILED", 1L);
        RefundPayment mockPending = payment(83L, 1L, 72L, "MOCK", "EXT-REV", 500L, "PENDING", 0L);
        Entitlement e100 = entitlement(100L, 101L, 3, 3, "AVAILABLE", LocalDate.of(2027, 12, 31));

        when(repository.findPaymentsByExternal("MOCK", "EXT-REV")).thenReturn(List.of(mockPending));
        when(repository.findById(1L, 72L)).thenReturn(Optional.of(processingRefund));
        when(tradingRepository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(orderBefore), Optional.of(orderAfter));
        when(tradingRepository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e100));
        when(repository.findForUpdate(1L, 72L)).thenReturn(Optional.of(processingRefund));
        when(repository.findPayments(1L, 72L)).thenReturn(List.of(bankFailed, mockPending));
        when(repository.findPaymentByExternal(1L, "MOCK", "EXT-REV")).thenReturn(Optional.of(mockPending));
        when(repository.findPaymentForUpdate(1L, 83L)).thenReturn(Optional.of(mockPending));
        when(repository.findItems(1L, 72L)).thenReturn(List.of(new RefundItem(1L, 1L, 72L, 100L, 1, 500L)));
        when(tradingRepository.findOrderItems(1L, 10L)).thenReturn(List.of(
                new OrderItem(101L, 10L, 1L, 1L, "S1", "N1", 1, 1000L, 1000L, "{}", 0L)));
        when(tradingRepository.updateEntitlementBalance(1L, 100L, 0L, 2, "AVAILABLE", 1)).thenReturn(1);
        when(tradingRepository.sumLedgerForUpdate(1L, 100L)).thenReturn(2L);
        when(tradingRepository.addRefundedFen(1L, 10L, 0L, 500L)).thenReturn(1);
        when(repository.updatePaymentStatus(1L, 83L, 0L, "PENDING", "SUCCEEDED", NOW, "MOCK-OK", null))
                .thenReturn(1);
        when(repository.updateStatus(1L, 72L, 4L, "PROCESSING", "SUCCEEDED", null, 500L, null, null, NOW))
                .thenReturn(1);

        service.handleCallback("MOCK",
                new RefundCallbackRequest("EXT-REV", 500L, "SUCCEEDED", NOW, "MOCK-OK"), "cb-rev-mock");

        verify(repository).updatePaymentStatus(1L, 83L, 0L, "PENDING", "SUCCEEDED", NOW, "MOCK-OK", null);
    }

    private Order order(long orderId, String status, long paidFen, long refundedFen, long version) {
        return new Order(orderId, "ORD-" + orderId, 99L, "客户", 2L, 2L, null, "CRM", null,
                "REGULAR", null, 1L, 1L, null, null, null, null, null, null,
                status, "PLATFORM", paidFen, 0L, paidFen, paidFen, refundedFen, NOW, NOW, NOW, version);
    }

    private Refund refund(long id, long tenantId, long orderId, long requestedFen, long approvedFen,
                          String status, long version) {
        return new Refund(id, tenantId, "RF-" + id, orderId, 99L, requestedFen, approvedFen, 0L,
                "CUSTOMER_REQUEST", "detail", status, MANAGER.userId(),
                "REQUESTED".equals(status) ? null : FINANCE.userId(),
                "PROCESSING".equals(status) ? FINANCE.userId() : null,
                null, NOW, NOW, version);
    }

    private RefundPayment payment(long id, long tenantId, long refundId, String provider, String externalNo,
                                  long amountFen, String status, long version) {
        return new RefundPayment(id, tenantId, refundId, provider, externalNo, amountFen, status,
                "MOCK".equals(provider) ? "PROVIDER_CALLBACK" : "MANUAL_REVIEW",
                null, null, null, NOW, NOW, version);
    }

    private Entitlement entitlement(long id, long orderItemId, int granted, int available,
                                    String status, LocalDate validTo) {
        return new Entitlement(id, 99L, orderItemId, 101L, "项目", granted, available, 0,
                LocalDate.of(2026, 1, 1), validTo, status, NOW, 0L);
    }
}
