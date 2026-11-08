package com.whale.crm.trading.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TradingRepository {
    void insertOrder(Order order);
    int backfillOrderNo(long tenantId, long id, String orderNo);
    Optional<Order> findOrder(long tenantId, long id);
    Optional<Order> findOrderByNo(long tenantId, String orderNo);
    Optional<Order> findOrderForUpdate(long tenantId, long id);
    int updateOrderStatus(long tenantId, long id, long expectedVersion, String fromStatus, String toStatus,
                          Long paidFen, Instant paidAt);
    int addRefundedFen(long tenantId, long id, long expectedVersion, long amountFen);
    List<Order> searchOrders(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                             Long customerId, String status, int offset, int size);
    long countOrders(long tenantId, String scopeType, List<Long> teamIds, long selfId, Long customerId, String status);

    void insertOrderItem(OrderItem item);
    Optional<OrderItem> findOrderItem(long tenantId, long id);
    List<OrderItem> findOrderItems(long tenantId, long orderId);
    void insertPayment(PaymentTxn payment);
    Optional<PaymentTxn> findPaymentByTradeNo(long tenantId, String channel, String tradeNo);
    List<PaymentTxn> findPaymentsByOrder(long tenantId, long orderId);
    void insertStatusHistory(OrderStatusHistory history);
    List<OrderStatusHistory> findStatusHistory(long tenantId, long orderId);

    void insertEntitlement(Entitlement entitlement);
    List<Entitlement> findEntitlementsByOrderItemIds(long tenantId, List<Long> orderItemIds);
    Optional<Entitlement> findEntitlement(long tenantId, long id);
    Optional<Entitlement> findEntitlementForUpdate(long tenantId, long id);
    int updateEntitlementBalance(long tenantId, long id, long expectedVersion, int availableCount,
                                 String status, int versionNo);
    List<Entitlement> searchEntitlements(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                         Long customerId, String status, int offset, int size);
    long countEntitlements(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                           Long customerId, String status);
    EntitlementSummary summarizeEntitlements(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                             Long customerId, java.time.LocalDate today);
    void insertLedger(EntitlementLedgerEntry entry);
    Optional<EntitlementLedgerEntry> findLedgerByKey(long tenantId, String idempotencyKey);
    List<EntitlementLedgerEntry> findLedgerBySource(long tenantId, String sourceType, String sourceRef);
    List<EntitlementLedgerEntry> findLedgerByEntitlement(long tenantId, long entitlementId);
    long sumLedger(long tenantId, long entitlementId);

    Optional<OutboxEvent> findOutboxByEventId(long tenantId, String eventId);
    Optional<OutboxEvent> findOutboxById(long tenantId, long id);
    void insertOutboxEvent(OutboxEvent event);
    List<Long> findOutboxTenantIds(Instant now);
    List<OutboxEvent> findPendingOutbox(long tenantId, Instant now, int limit);
    int claimOutboxEvent(long tenantId, long id, long expectedVersion, String claimOwner,
                         Instant now, Instant claimExpiresAt);
    int markOutboxSent(long tenantId, long id, long expectedVersion, String claimOwner, Instant sentAt);
    int markOutboxFailed(long tenantId, long id, long expectedVersion, String claimOwner,
                         String error, Instant nextRetryAt);

    OrderStats orderStats(long tenantId, long customerId);
    void reassignOrders(long tenantId, long fromCustomerId, long toCustomerId);
    void reassignEntitlements(long tenantId, long fromCustomerId, long toCustomerId);
    java.util.List<Long> findOrderIdsByCustomer(long tenantId, long customerId);
    java.util.List<Long> findEntitlementIdsByCustomer(long tenantId, long customerId);
    void reassignSpecificOrders(long tenantId, java.util.List<Long> orderIds, long fromCustomerId, long toCustomerId);
    void reassignSpecificEntitlements(long tenantId, java.util.List<Long> entitlementIds, long fromCustomerId, long toCustomerId);
}
