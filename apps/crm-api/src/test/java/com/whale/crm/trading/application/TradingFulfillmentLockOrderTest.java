package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.LockedEntitlementBatch;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingFulfillmentLockOrderTest {
    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");

    @Test
    void lockRedeemablesAndAppendRedemptionsLockOrdersBeforeEntitlementsAndRecalculateOncePerOrder() {
        TradingRepository repository = mock(TradingRepository.class);
        OutboxService outbox = mock(OutboxService.class);
        TradingFulfillmentApplicationService service = new TradingFulfillmentApplicationService(
                repository, mock(CustomerScopeSupport.class), outbox, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Order order10 = paidOrder(10L, "PAID");
        Order order20 = paidOrder(20L, "PAID");
        OrderItem itemForE100 = orderItem(201L, 20L, 101L);
        OrderItem itemForE200 = orderItem(101L, 10L, 102L);
        Entitlement e100 = entitlement(100L, 201L, 101L, 2, 2);
        Entitlement e200 = entitlement(200L, 101L, 102L, 3, 3);
        Entitlement e100After = entitlement(100L, 201L, 101L, 2, 1);
        Entitlement e200After = entitlement(200L, 101L, 102L, 3, 2);

        when(repository.findEntitlement(1L, 100L)).thenReturn(Optional.of(e100), Optional.of(e100After));
        when(repository.findEntitlement(1L, 200L)).thenReturn(Optional.of(e200), Optional.of(e200After));
        when(repository.findOrderItem(1L, 201L)).thenReturn(Optional.of(itemForE100));
        when(repository.findOrderItem(1L, 101L)).thenReturn(Optional.of(itemForE200));
        when(repository.findOrdersForUpdate(1L, List.of(10L, 20L))).thenReturn(List.of(order10, order20));
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L, 20L))).thenReturn(List.of(e100, e200));

        LockedEntitlementBatch batch = service.lockRedeemables(1L, List.of(
                new FulfillmentTradingOperations.RedemptionLockCommand(100L, 99L, 1L, 101L, 1, NOW),
                new FulfillmentTradingOperations.RedemptionLockCommand(200L, 99L, 1L, 102L, 1, NOW)));

        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).findOrdersForUpdate(1L, List.of(10L, 20L));
        lockOrder.verify(repository).findEntitlementsByOrderIdsForUpdate(1L, List.of(10L, 20L));

        when(repository.updateEntitlementBalance(1L, 100L, 0L, 1, "AVAILABLE", 1)).thenReturn(1);
        when(repository.updateEntitlementBalance(1L, 200L, 0L, 2, "AVAILABLE", 1)).thenReturn(1);
        when(repository.sumLedgerForUpdate(1L, 100L)).thenReturn(1L);
        when(repository.sumLedgerForUpdate(1L, 200L)).thenReturn(2L);
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e200After));
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(20L))).thenReturn(List.of(e100After));
        when(repository.findLedgerByEntitlementForUpdate(1L, 200L)).thenReturn(List.of(
                new EntitlementLedgerEntry(1L, 200L, "REDEEM", -1, "REDEMPTION", "502",
                        "redemption.post:502", 7L, "ok", NOW, 0L)));
        when(repository.findLedgerByEntitlementForUpdate(1L, 100L)).thenReturn(List.of(
                new EntitlementLedgerEntry(2L, 100L, "REDEEM", -1, "REDEMPTION", "501",
                        "redemption.post:501", 7L, "ok", NOW, 0L)));
        when(repository.updateOrderStatus(eq(1L), anyLong(), eq(0L), eq("PAID"), eq("PARTIALLY_REDEEMED"),
                any(), any())).thenReturn(1);

        service.appendRedemptions(1L, 7L, batch, List.of(
                new FulfillmentTradingOperations.RedemptionAppendCommand(100L, 1, "501", "redemption.post:501"),
                new FulfillmentTradingOperations.RedemptionAppendCommand(200L, 1, "502", "redemption.post:502")));

        verify(repository, never()).findOrderForUpdate(anyLong(), anyLong());
        verify(repository, never()).sumLedger(anyLong(), anyLong());
        verify(repository, never()).findLedgerByEntitlement(anyLong(), anyLong());
        verify(repository, never()).findEntitlementsByOrderItemIds(anyLong(), any());
        InOrder updateOrder = inOrder(repository);
        updateOrder.verify(repository).updateOrderStatus(1L, 10L, 0L, "PAID", "PARTIALLY_REDEEMED", null, null);
        updateOrder.verify(repository).insertStatusHistory(eq(1L),
                org.mockito.ArgumentMatchers.argThat(h -> h.orderId().equals(10L) && "502".equals(h.triggerRef())));
        updateOrder.verify(repository).updateOrderStatus(1L, 20L, 0L, "PAID", "PARTIALLY_REDEEMED", null, null);
        updateOrder.verify(repository).insertStatusHistory(eq(1L),
                org.mockito.ArgumentMatchers.argThat(h -> h.orderId().equals(20L) && "501".equals(h.triggerRef())));
    }

    @Test
    void lockReversiblesAndAppendReversalsRespectOrderAndDeduplicateSameOrderRecalculation() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingFulfillmentApplicationService service = new TradingFulfillmentApplicationService(
                repository, mock(CustomerScopeSupport.class), mock(OutboxService.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Order order10 = paidOrder(10L, "FULFILLED");
        OrderItem item101 = orderItem(101L, 10L, 101L);
        OrderItem item102 = orderItem(102L, 10L, 102L);
        Entitlement e1 = entitlement(100L, 101L, 101L, 1, 0);
        Entitlement e2 = entitlement(200L, 102L, 102L, 1, 0);
        Entitlement e1After = entitlement(100L, 101L, 101L, 1, 1);
        Entitlement e2After = entitlement(200L, 102L, 102L, 1, 1);

        when(repository.findEntitlement(1L, 100L)).thenReturn(Optional.of(e1), Optional.of(e1After));
        when(repository.findEntitlement(1L, 200L)).thenReturn(Optional.of(e2), Optional.of(e2After));
        when(repository.findOrderItem(1L, 101L)).thenReturn(Optional.of(item101));
        when(repository.findOrderItem(1L, 102L)).thenReturn(Optional.of(item102));
        when(repository.findOrdersForUpdate(1L, List.of(10L))).thenReturn(List.of(order10));
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e1, e2));
        when(repository.findLedgerBySource(1L, "REDEMPTION", "501")).thenReturn(List.of(
                new EntitlementLedgerEntry(1L, 100L, "REDEEM", -1, "REDEMPTION", "501",
                        "redemption.post:501", 7L, "ok", NOW, 0L)));
        when(repository.findLedgerBySource(1L, "REDEMPTION", "502")).thenReturn(List.of(
                new EntitlementLedgerEntry(2L, 200L, "REDEEM", -1, "REDEMPTION", "502",
                        "redemption.post:502", 7L, "ok", NOW, 0L)));

        LockedEntitlementBatch batch = service.lockReversibles(1L, List.of(
                new FulfillmentTradingOperations.ReversalLockCommand(100L, 99L, 101L, "501", 1),
                new FulfillmentTradingOperations.ReversalLockCommand(200L, 99L, 102L, "502", 1)));

        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).findOrdersForUpdate(1L, List.of(10L));
        lockOrder.verify(repository).findEntitlementsByOrderIdsForUpdate(1L, List.of(10L));

        when(repository.updateEntitlementBalance(1L, 100L, 0L, 1, "AVAILABLE", 1)).thenReturn(1);
        when(repository.updateEntitlementBalance(1L, 200L, 0L, 1, "AVAILABLE", 1)).thenReturn(1);
        when(repository.sumLedgerForUpdate(1L, 100L)).thenReturn(1L);
        when(repository.sumLedgerForUpdate(1L, 200L)).thenReturn(1L);
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(e1After, e2After));
        when(repository.findLedgerByEntitlementForUpdate(1L, 100L)).thenReturn(List.of());
        when(repository.findLedgerByEntitlementForUpdate(1L, 200L)).thenReturn(List.of());
        when(repository.updateOrderStatus(1L, 10L, 0L, "FULFILLED", "PAID", null, null)).thenReturn(1);

        service.appendReversals(1L, 5L, batch, List.of(
                new FulfillmentTradingOperations.ReversalAppendCommand(100L, 1, "900", "redemption.reversal:900:501"),
                new FulfillmentTradingOperations.ReversalAppendCommand(200L, 1, "900", "redemption.reversal:900:502")));

        verify(repository, never()).findOrderForUpdate(anyLong(), anyLong());
        verify(repository, never()).sumLedger(anyLong(), anyLong());
        verify(repository, never()).findLedgerByEntitlement(anyLong(), anyLong());
        verify(repository, never()).findEntitlementsByOrderItemIds(anyLong(), any());
        verify(repository, times(1)).updateOrderStatus(eq(1L), eq(10L), anyLong(), anyString(), anyString(), any(), any());
        verify(repository, times(1)).insertStatusHistory(eq(1L),
                org.mockito.ArgumentMatchers.argThat(h -> "900".equals(h.triggerRef())));
        assertThat(batch.ordersById()).containsOnlyKeys(10L);
        assertThat(batch.viewsByEntitlementId()).containsOnlyKeys(100L, 200L);
        verify(repository, never()).updateEntitlementBalance(eq(1L), anyLong(), anyLong(), anyInt(), eq("EXHAUSTED"), anyInt());
    }

    @Test
    void recalculateOrderStatusAfterRefundTransitionsPartiallyRedeemedToFulfilled() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingFulfillmentApplicationService service = new TradingFulfillmentApplicationService(
                repository, mock(CustomerScopeSupport.class), mock(OutboxService.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Order order10 = paidOrder(10L, "PARTIALLY_REDEEMED");
        Entitlement ent = entitlement(100L, 101L, 101L, 3, 0);
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(ent));
        when(repository.sumLedgerForUpdate(1L, 100L)).thenReturn(0L);
        when(repository.findLedgerByEntitlementForUpdate(1L, 100L)).thenReturn(List.of(
                new EntitlementLedgerEntry(1L, 100L, "GRANT", 3, "ORDER", "10", "grant:10", 1L, "ok", NOW, 0L),
                new EntitlementLedgerEntry(2L, 100L, "REDEEM", -1, "REDEMPTION", "501", "redemption.post:501", 7L, "ok", NOW, 0L),
                new EntitlementLedgerEntry(3L, 100L, "REFUND_REVOKE", -2, "REFUND", "700", "refund.revoke:700:100", null, "ok", NOW, 0L)));
        when(repository.updateOrderStatus(1L, 10L, 0L, "PARTIALLY_REDEEMED", "FULFILLED", null, null)).thenReturn(1);

        service.recalculateOrderStatusAfterRefund(1L, null, order10, 700L);

        verify(repository).updateOrderStatus(1L, 10L, 0L, "PARTIALLY_REDEEMED", "FULFILLED", null, null);
        verify(repository).insertStatusHistory(eq(1L),
                org.mockito.ArgumentMatchers.argThat(h -> h.orderId().equals(10L)
                        && "PARTIALLY_REDEEMED".equals(h.fromStatus())
                        && "FULFILLED".equals(h.toStatus())
                        && h.actorUserId() == null
                        && "REFUND_SUCCEEDED".equals(h.triggerType())
                        && "700".equals(h.triggerRef())));
    }

    @Test
    void recalculateOrderStatusAfterRefundKeepsPaidWhenZeroRedeemedEvenIfAllRevoked() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingFulfillmentApplicationService service = new TradingFulfillmentApplicationService(
                repository, mock(CustomerScopeSupport.class), mock(OutboxService.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Order order10 = paidOrder(10L, "PAID");
        Entitlement ent = new Entitlement(100L, 99L, 101L, 101L, "项目101", 3, 0, 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), "REVOKED", NOW, 1L);
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L))).thenReturn(List.of(ent));
        when(repository.sumLedgerForUpdate(1L, 100L)).thenReturn(0L);
        when(repository.findLedgerByEntitlementForUpdate(1L, 100L)).thenReturn(List.of(
                new EntitlementLedgerEntry(1L, 100L, "GRANT", 3, "ORDER", "10", "grant:10", 1L, "ok", NOW, 0L),
                new EntitlementLedgerEntry(2L, 100L, "REFUND_REVOKE", -3, "REFUND", "700", "refund.revoke:700:100", 5L, "ok", NOW, 0L)));

        service.recalculateOrderStatusAfterRefund(1L, 5L, order10, 700L);

        verify(repository, never()).updateOrderStatus(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any());
        verify(repository, never()).insertStatusHistory(anyLong(), any());
    }

    private Order paidOrder(long orderId, String status) {
        return new Order(orderId, "J" + orderId, 99L, null, 1L, 2L, null, "CRM", null,
                "REGULAR", null, null, status, "PLATFORM", 1000L, 0L, 1000L, 1000L, 0L,
                NOW, NOW, NOW, 0L);
    }

    private OrderItem orderItem(long itemId, long orderId, long projectId) {
        return new OrderItem(itemId, orderId, 301L, 301L, "SKU-301", "套餐", 1, 1000L, 1000L,
                "{\"projectSnapshots\":[{\"projectId\":" + projectId + ",\"grantCount\":2}],\"institutionIds\":[1],\"allocationStatus\":\"CONFIGURED\"}",
                0L);
    }

    private Entitlement entitlement(long id, long orderItemId, long projectId, int granted, int available) {
        return new Entitlement(id, 99L, orderItemId, projectId, "项目" + projectId, granted, available, 0,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), available == 0 ? "EXHAUSTED" : "AVAILABLE",
                NOW, 0L);
    }
}
