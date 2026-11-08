package com.whale.crm.trading.infrastructure;

import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderStats;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.PaymentTxn;
import com.whale.crm.trading.domain.TradingRepository;
import com.whale.crm.trading.infrastructure.mapper.TradingMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisTradingRepository implements TradingRepository {
    private final TradingMapper mapper;

    public MybatisTradingRepository(TradingMapper mapper) {
        this.mapper = mapper;
    }

    public void insertOrder(Order order) { mapper.insertOrder(com.whale.crm.platform.context.TenantContext.currentTenantId(), order); }
    public int backfillOrderNo(long tenantId, long id, String orderNo) { return mapper.backfillOrderNo(tenantId, id, orderNo); }
    public Optional<Order> findOrder(long tenantId, long id) { return Optional.ofNullable(mapper.selectOrder(tenantId, id)); }
    public Optional<Order> findOrderByNo(long tenantId, String orderNo) { return Optional.ofNullable(mapper.selectOrderByNo(tenantId, orderNo)); }
    public Optional<Order> findOrderForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectOrderForUpdate(tenantId, id)); }
    public int updateOrderStatus(long tenantId, long id, long expectedVersion, String fromStatus, String toStatus, Long paidFen, Instant paidAt) {
        return mapper.updateOrderStatus(tenantId, id, expectedVersion, fromStatus, toStatus, paidFen, paidAt);
    }
    public int addRefundedFen(long tenantId, long id, long expectedVersion, long amountFen) {
        return mapper.addRefundedFen(tenantId, id, expectedVersion, amountFen);
    }
    public List<Order> searchOrders(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, String status, int offset, int size) {
        return mapper.searchOrders(tenantId, scopeType, teamIds, selfId, customerId, status, offset, size);
    }
    public long countOrders(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, String status) {
        return mapper.countOrders(tenantId, scopeType, teamIds, selfId, customerId, status);
    }

    public void insertOrderItem(OrderItem item) { mapper.insertOrderItem(com.whale.crm.platform.context.TenantContext.currentTenantId(), item); }
    public Optional<OrderItem> findOrderItem(long tenantId, long id) { return Optional.ofNullable(mapper.selectOrderItem(tenantId, id)); }
    public List<OrderItem> findOrderItems(long tenantId, long orderId) { return mapper.selectOrderItems(tenantId, orderId); }
    public void insertPayment(PaymentTxn payment) { mapper.insertPayment(com.whale.crm.platform.context.TenantContext.currentTenantId(), payment); }
    public Optional<PaymentTxn> findPaymentByTradeNo(long tenantId, String channel, String tradeNo) { return Optional.ofNullable(mapper.selectPaymentByTradeNo(tenantId, channel, tradeNo)); }
    public List<PaymentTxn> findPaymentsByOrder(long tenantId, long orderId) { return mapper.selectPaymentsByOrder(tenantId, orderId); }
    public void insertStatusHistory(OrderStatusHistory history) { mapper.insertStatusHistory(com.whale.crm.platform.context.TenantContext.currentTenantId(), history); }
    public List<OrderStatusHistory> findStatusHistory(long tenantId, long orderId) { return mapper.selectStatusHistory(tenantId, orderId); }

    public void insertEntitlement(Entitlement entitlement) { mapper.insertEntitlement(com.whale.crm.platform.context.TenantContext.currentTenantId(), entitlement); }
    public List<Entitlement> findEntitlementsByOrderItemIds(long tenantId, List<Long> orderItemIds) { return mapper.selectEntitlementsByOrderItemIds(tenantId, orderItemIds); }
    public Optional<Entitlement> findEntitlement(long tenantId, long id) { return Optional.ofNullable(mapper.selectEntitlement(tenantId, id)); }
    public Optional<Entitlement> findEntitlementForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectEntitlementForUpdate(tenantId, id)); }
    public int updateEntitlementBalance(long tenantId, long id, long expectedVersion, int availableCount, String status, int versionNo) {
        return mapper.updateEntitlementBalance(tenantId, id, expectedVersion, availableCount, status, versionNo);
    }
    public List<Entitlement> searchEntitlements(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, String status, int offset, int size) {
        return mapper.searchEntitlements(tenantId, scopeType, teamIds, selfId, customerId, status, offset, size);
    }
    public long countEntitlements(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, String status) {
        return mapper.countEntitlements(tenantId, scopeType, teamIds, selfId, customerId, status);
    }
    public com.whale.crm.trading.domain.EntitlementSummary summarizeEntitlements(
            long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, java.time.LocalDate today) {
        return mapper.summarizeEntitlements(tenantId, scopeType, teamIds, selfId, customerId, today);
    }
    public void insertLedger(EntitlementLedgerEntry entry) { mapper.insertLedger(com.whale.crm.platform.context.TenantContext.currentTenantId(), entry); }
    public Optional<EntitlementLedgerEntry> findLedgerByKey(long tenantId, String idempotencyKey) { return Optional.ofNullable(mapper.selectLedgerByKey(tenantId, idempotencyKey)); }
    public List<EntitlementLedgerEntry> findLedgerBySource(long tenantId, String sourceType, String sourceRef) {
        return mapper.selectLedgerBySource(tenantId, sourceType, sourceRef);
    }
    public List<EntitlementLedgerEntry> findLedgerByEntitlement(long tenantId, long entitlementId) { return mapper.selectLedgerByEntitlement(tenantId, entitlementId); }
    public long sumLedger(long tenantId, long entitlementId) {
        Long value = mapper.sumLedger(tenantId, entitlementId);
        return value == null ? 0L : value;
    }

    public Optional<OutboxEvent> findOutboxByEventId(long tenantId, String eventId) { return Optional.ofNullable(mapper.selectOutboxByEventId(tenantId, eventId)); }
    public Optional<OutboxEvent> findOutboxById(long tenantId, long id) { return Optional.ofNullable(mapper.selectOutboxById(tenantId, id)); }
    public void insertOutboxEvent(OutboxEvent event) { mapper.insertOutboxEvent(event.tenantId(), event); }
    public List<Long> findOutboxTenantIds(Instant now) { return mapper.selectOutboxTenantIds(now); }
    public List<OutboxEvent> findPendingOutbox(long tenantId, Instant now, int limit) { return mapper.selectPendingOutbox(tenantId, now, limit); }
    public int claimOutboxEvent(long tenantId, long id, long expectedVersion, String claimOwner, Instant now, Instant claimExpiresAt) {
        return mapper.claimOutboxEvent(tenantId, id, expectedVersion, claimOwner, now, claimExpiresAt);
    }
    public int markOutboxSent(long tenantId, long id, long expectedVersion, String claimOwner, Instant sentAt) {
        return mapper.markOutboxSent(tenantId, id, expectedVersion, claimOwner, sentAt);
    }
    public int markOutboxFailed(long tenantId, long id, long expectedVersion, String claimOwner, String error, Instant nextRetryAt) {
        return mapper.markOutboxFailed(tenantId, id, expectedVersion, claimOwner, error, nextRetryAt);
    }

    public OrderStats orderStats(long tenantId, long customerId) { return mapper.orderStats(tenantId, customerId); }
    public void reassignOrders(long tenantId, long fromCustomerId, long toCustomerId) { mapper.reassignOrders(tenantId, fromCustomerId, toCustomerId); }
    public void reassignEntitlements(long tenantId, long fromCustomerId, long toCustomerId) { mapper.reassignEntitlements(tenantId, fromCustomerId, toCustomerId); }
    public java.util.List<Long> findOrderIdsByCustomer(long tenantId, long customerId) { return mapper.findOrderIdsByCustomer(tenantId, customerId); }
    public java.util.List<Long> findEntitlementIdsByCustomer(long tenantId, long customerId) { return mapper.findEntitlementIdsByCustomer(tenantId, customerId); }
    public void reassignSpecificOrders(long tenantId, java.util.List<Long> orderIds, long fromCustomerId, long toCustomerId) { mapper.reassignSpecificOrders(tenantId, orderIds, fromCustomerId, toCustomerId); }
    public void reassignSpecificEntitlements(long tenantId, java.util.List<Long> entitlementIds, long fromCustomerId, long toCustomerId) { mapper.reassignSpecificEntitlements(tenantId, entitlementIds, fromCustomerId, toCustomerId); }
}
