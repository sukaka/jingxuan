package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerOrderOperations;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.PaymentTxn;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentApplicationServiceTest {

    private static final CurrentUser ACTOR = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
            Set.of("ADMIN"), Set.of("order:payment_confirm"), Set.of());

    @Test
    void doConfirmLocksCustomerForUpdateBeforeLockingOrder() {
        Fixture fixture = new Fixture();
        Order order = pendingOrder(10L, 200L, 181100L);
        PaymentTxn payment = confirmedPayment(50L, 10L, "TRADE-1", 181100L);
        OrderFulfillmentView view = paidView(order);

        when(fixture.idempotencyService.lookup(eq(1L), eq("payment.confirm"), eq("KEY-1"), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.repository.findOrder(1L, 10L)).thenReturn(Optional.of(order));
        when(fixture.scopeSupport.requireVisibleForUpdate(1L, 200L, ACTOR)).thenReturn(new Customer());
        when(fixture.repository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(order));
        when(fixture.repository.findPaymentByTradeNo(1L, "BANK_TRANSFER", "TRADE-1"))
                .thenReturn(Optional.empty(), Optional.of(payment));
        when(fixture.repository.updateOrderStatus(eq(1L), eq(10L), eq(0L), eq("PENDING_PAYMENT"),
                eq("PAID"), eq(181100L), any())).thenReturn(1);
        when(fixture.repository.findOrderItems(1L, 10L)).thenReturn(List.of());
        when(fixture.orderService.detail(1L, ACTOR, 10L)).thenReturn(view);

        PaymentConfirmRequest request = new PaymentConfirmRequest("TRADE-1", "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "PROOF-1", null);
        OrderFulfillmentView result = fixture.service.confirm(1L, ACTOR, 10L, request, "KEY-1");

        assertThat(result).isSameAs(view);
        InOrder orderOfLocks = inOrder(fixture.scopeSupport, fixture.repository, fixture.customerOrderOperations);
        orderOfLocks.verify(fixture.repository).findOrder(1L, 10L);
        orderOfLocks.verify(fixture.scopeSupport).requireVisibleForUpdate(1L, 200L, ACTOR);
        orderOfLocks.verify(fixture.repository).findOrderForUpdate(1L, 10L);
        orderOfLocks.verify(fixture.customerOrderOperations)
                .recordFirstOrder(1L, ACTOR, 200L, request.paidAt());
        verify(fixture.scopeSupport, never()).requireVisible(anyLong(), anyLong(), any());
    }

    @Test
    void confirmRetriesWhenCustomerWasMergedBeforeLockAndSucceedsOnPrimaryCustomer() {
        Fixture fixture = new Fixture();
        Order secondaryOrder = pendingOrder(10L, 200L, 181100L);
        Order primaryOrder = pendingOrder(10L, 100L, 181100L);
        PaymentTxn payment = confirmedPayment(51L, 10L, "TRADE-2", 181100L);
        OrderFulfillmentView view = paidView(primaryOrder);

        when(fixture.idempotencyService.lookup(eq(1L), eq("payment.confirm"), eq("KEY-2"), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.repository.findOrder(1L, 10L)).thenReturn(Optional.of(secondaryOrder));
        when(fixture.scopeSupport.requireVisibleForUpdate(1L, 200L, ACTOR))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        when(fixture.scopeSupport.requireVisibleForUpdate(1L, 100L, ACTOR))
                .thenReturn(new Customer());
        when(fixture.repository.findOrderForUpdate(1L, 10L))
                .thenReturn(Optional.of(primaryOrder), Optional.of(primaryOrder));
        when(fixture.repository.findPaymentByTradeNo(1L, "BANK_TRANSFER", "TRADE-2"))
                .thenReturn(Optional.empty(), Optional.of(payment));
        when(fixture.repository.updateOrderStatus(eq(1L), eq(10L), eq(0L), eq("PENDING_PAYMENT"),
                eq("PAID"), eq(181100L), any())).thenReturn(1);
        when(fixture.repository.findOrderItems(1L, 10L)).thenReturn(List.of());
        when(fixture.orderService.detail(1L, ACTOR, 10L)).thenReturn(view);

        PaymentConfirmRequest request = new PaymentConfirmRequest("TRADE-2", "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "PROOF-2", null);
        OrderFulfillmentView result = fixture.service.confirm(1L, ACTOR, 10L, request, "KEY-2");

        assertThat(result).isSameAs(view);
        verify(fixture.scopeSupport).requireVisibleForUpdate(1L, 200L, ACTOR);
        verify(fixture.scopeSupport).requireVisibleForUpdate(1L, 100L, ACTOR);
        verify(fixture.customerOrderOperations).recordFirstOrder(1L, ACTOR, 100L, request.paidAt());
    }

    @Test
    void confirmThrowsNotFoundWhenCustomerInvisibleWithoutReassignment() {
        Fixture fixture = new Fixture();
        Order order = pendingOrder(10L, 200L, 181100L);

        when(fixture.idempotencyService.lookup(eq(1L), eq("payment.confirm"), eq("KEY-3"), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.repository.findOrder(1L, 10L)).thenReturn(Optional.of(order));
        when(fixture.scopeSupport.requireVisibleForUpdate(1L, 200L, ACTOR))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        when(fixture.repository.findOrderForUpdate(1L, 10L)).thenReturn(Optional.of(order));

        PaymentConfirmRequest request = new PaymentConfirmRequest("TRADE-3", "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "PROOF-3", null);
        assertThatThrownBy(() -> fixture.service.confirm(1L, ACTOR, 10L, request, "KEY-3"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(fixture.scopeSupport, times(1)).requireVisibleForUpdate(anyLong(), anyLong(), any());
        verify(fixture.repository, never()).insertPayment(anyLong(), any());
    }

    @Test
    void confirmThrowsConflictAfterMaxRetriesOnRepeatedCustomerChanges() {
        Fixture fixture = new Fixture();
        when(fixture.idempotencyService.lookup(eq(1L), eq("payment.confirm"), eq("KEY-4"), anyString()))
                .thenReturn(Optional.empty());
        when(fixture.repository.findOrder(1L, 10L)).thenReturn(Optional.of(pendingOrder(10L, 200L, 181100L)));
        when(fixture.scopeSupport.requireVisibleForUpdate(eq(1L), anyLong(), eq(ACTOR)))
                .thenReturn(new Customer());
        when(fixture.repository.findOrderForUpdate(1L, 10L)).thenReturn(
                Optional.of(pendingOrder(10L, 201L, 181100L)),
                Optional.of(pendingOrder(10L, 202L, 181100L)),
                Optional.of(pendingOrder(10L, 203L, 181100L)));

        PaymentConfirmRequest request = new PaymentConfirmRequest("TRADE-4", "BANK_TRANSFER", 181100L,
                Instant.parse("2026-10-24T03:00:00Z"), "PROOF-4", null);
        assertThatThrownBy(() -> fixture.service.confirm(1L, ACTOR, 10L, request, "KEY-4"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(fixture.scopeSupport, times(3)).requireVisibleForUpdate(eq(1L), anyLong(), eq(ACTOR));
        verify(fixture.repository, never()).insertPayment(anyLong(), any());
    }

    private static Order pendingOrder(long id, long customerId, long payableFen) {
        return new Order(id, "J0000000000" + id, customerId, null, 1L, 2L, null, "CRM", null,
                "REGULAR", null, null, "PENDING_PAYMENT", "PLATFORM", payableFen, 0L, payableFen, 0L, 0L,
                Instant.now(), null, Instant.now(), 0L);
    }

    private static PaymentTxn confirmedPayment(long id, long orderId, String tradeNo, long amountFen) {
        return new PaymentTxn(id, orderId, "BANK_TRANSFER", "PLATFORM", tradeNo, amountFen, "CONFIRMED",
                Instant.now(), 1L, null, "PROOF", "MANUAL_REVIEW", Instant.now(), 0L);
    }

    private static OrderFulfillmentView paidView(Order order) {
        return new OrderFulfillmentView(new OrderDetail(order, List.of(), List.of(), List.of()), List.of());
    }

    private static final class Fixture {
        private final TradingRepository repository = mock(TradingRepository.class);
        private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
        private final OutboxService outboxService = mock(OutboxService.class);
        private final CustomerOrderOperations customerOrderOperations = mock(CustomerOrderOperations.class);
        private final CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        private final AuditService auditService = mock(AuditService.class);
        private final OrderApplicationService orderService = mock(OrderApplicationService.class);
        private final FileApplicationService fileOperations = mock(FileApplicationService.class);
        private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        private final PaymentApplicationService service;

        private Fixture() {
            when(txManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            this.service = new PaymentApplicationService(repository, idempotencyService, outboxService,
                    customerOrderOperations, scopeSupport, auditService, orderService, fileOperations,
                    new ObjectMapper(), txManager);
        }
    }
}
