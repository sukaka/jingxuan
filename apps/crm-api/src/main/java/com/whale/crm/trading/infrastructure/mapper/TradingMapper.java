package com.whale.crm.trading.infrastructure.mapper;

import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderStats;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.PaymentTxn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface TradingMapper {
    void insertOrder(@Param("tenantId") long tenantId, @Param("entity") Order order);
    int backfillOrderNo(@Param("tenantId") long tenantId, @Param("id") long id, @Param("orderNo") String orderNo);
    Order selectOrder(@Param("tenantId") long tenantId, @Param("id") long id);
    Order selectOrderByNo(@Param("tenantId") long tenantId, @Param("orderNo") String orderNo);
    Order selectOrderForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    int updateOrderStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                          @Param("expectedVersion") long expectedVersion, @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus, @Param("paidFen") Long paidFen, @Param("paidAt") Instant paidAt);
    int addRefundedFen(@Param("tenantId") long tenantId, @Param("id") long id,
                       @Param("expectedVersion") long expectedVersion, @Param("amountFen") long amountFen);
    List<Order> searchOrders(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                             @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                             @Param("customerId") Long customerId, @Param("status") String status,
                             @Param("offset") int offset, @Param("size") int size);
    long countOrders(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                     @Param("customerId") Long customerId, @Param("status") String status);

    void insertOrderItem(@Param("tenantId") long tenantId, @Param("entity") OrderItem item);
    OrderItem selectOrderItem(@Param("tenantId") long tenantId, @Param("id") long id);
    List<OrderItem> selectOrderItems(@Param("tenantId") long tenantId, @Param("orderId") long orderId);
    void insertPayment(@Param("tenantId") long tenantId, @Param("entity") PaymentTxn payment);
    PaymentTxn selectPaymentByTradeNo(@Param("tenantId") long tenantId, @Param("channel") String channel,
                                      @Param("tradeNo") String tradeNo);
    List<PaymentTxn> selectPaymentsByOrder(@Param("tenantId") long tenantId, @Param("orderId") long orderId);
    void insertStatusHistory(@Param("tenantId") long tenantId, @Param("entity") OrderStatusHistory history);
    List<OrderStatusHistory> selectStatusHistory(@Param("tenantId") long tenantId, @Param("orderId") long orderId);

    void insertEntitlement(@Param("tenantId") long tenantId, @Param("entity") Entitlement entitlement);
    List<Entitlement> selectEntitlementsByOrderItemIds(@Param("tenantId") long tenantId, @Param("orderItemIds") List<Long> orderItemIds);
    Entitlement selectEntitlement(@Param("tenantId") long tenantId, @Param("id") long id);
    Entitlement selectEntitlementForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    int updateEntitlementBalance(@Param("tenantId") long tenantId, @Param("id") long id,
                                 @Param("expectedVersion") long expectedVersion, @Param("availableCount") int availableCount,
                                 @Param("status") String status, @Param("versionNo") int versionNo);
    List<Entitlement> searchEntitlements(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                                         @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                         @Param("customerId") Long customerId, @Param("status") String status,
                                         @Param("offset") int offset, @Param("size") int size);
    long countEntitlements(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
                           @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                           @Param("customerId") Long customerId, @Param("status") String status);
    com.whale.crm.trading.domain.EntitlementSummary summarizeEntitlements(
            @Param("tenantId") long tenantId, @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
            @Param("customerId") Long customerId, @Param("today") java.time.LocalDate today);
    void insertLedger(@Param("tenantId") long tenantId, @Param("entity") EntitlementLedgerEntry entry);
    EntitlementLedgerEntry selectLedgerByKey(@Param("tenantId") long tenantId, @Param("idempotencyKey") String idempotencyKey);
    List<EntitlementLedgerEntry> selectLedgerBySource(@Param("tenantId") long tenantId,
                                                      @Param("sourceType") String sourceType,
                                                      @Param("sourceRef") String sourceRef);
    List<EntitlementLedgerEntry> selectLedgerByEntitlement(@Param("tenantId") long tenantId, @Param("entitlementId") long entitlementId);
    Long sumLedger(@Param("tenantId") long tenantId, @Param("entitlementId") long entitlementId);

    OutboxEvent selectOutboxByEventId(@Param("tenantId") long tenantId, @Param("eventId") String eventId);
    OutboxEvent selectOutboxById(@Param("tenantId") long tenantId, @Param("id") long id);
    void insertOutboxEvent(@Param("tenantId") long tenantId, @Param("entity") OutboxEvent event);
    List<Long> selectOutboxTenantIds(@Param("now") Instant now);
    List<OutboxEvent> selectPendingOutbox(@Param("tenantId") long tenantId, @Param("now") Instant now,
                                          @Param("limit") int limit);
    int claimOutboxEvent(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion, @Param("claimOwner") String claimOwner,
                         @Param("now") Instant now, @Param("claimExpiresAt") Instant claimExpiresAt);
    int markOutboxSent(@Param("tenantId") long tenantId, @Param("id") long id,
                       @Param("expectedVersion") long expectedVersion, @Param("claimOwner") String claimOwner,
                       @Param("sentAt") Instant sentAt);
    int markOutboxFailed(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion, @Param("claimOwner") String claimOwner,
                         @Param("error") String error, @Param("nextRetryAt") Instant nextRetryAt);

    OrderStats orderStats(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
    void reassignOrders(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                        @Param("toCustomerId") long toCustomerId);
    void reassignEntitlements(@Param("tenantId") long tenantId, @Param("fromCustomerId") long fromCustomerId,
                              @Param("toCustomerId") long toCustomerId);
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_order WHERE tenant_id=#{tenantId} AND customer_id=#{customerId} ORDER BY id FOR UPDATE")
    java.util.List<Long> findOrderIdsByCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
    @org.apache.ibatis.annotations.Select("SELECT id FROM crm_entitlement WHERE tenant_id=#{tenantId} AND customer_id=#{customerId} ORDER BY id FOR UPDATE")
    java.util.List<Long> findEntitlementIdsByCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId);
    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_order SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    void reassignSpecificOrders(@Param("tenantId") long tenantId, @Param("ids") java.util.List<Long> ids,
                                @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);
    @org.apache.ibatis.annotations.Update({"<script>UPDATE crm_entitlement SET customer_id=#{toCustomerId} WHERE tenant_id=#{tenantId} AND customer_id=#{fromCustomerId} AND id IN <foreach item='id' collection='ids' open='(' close=')' separator=','>#{id}</foreach></script>"})
    void reassignSpecificEntitlements(@Param("tenantId") long tenantId, @Param("ids") java.util.List<Long> ids,
                                      @Param("fromCustomerId") long fromCustomerId, @Param("toCustomerId") long toCustomerId);
}
