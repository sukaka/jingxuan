package com.whale.crm.refund.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefundRepository {
    void insertRefund(Refund refund);
    Optional<Refund> findByNo(long tenantId, String refundNo);
    Optional<Refund> findById(long tenantId, long id);
    Optional<Refund> findForUpdate(long tenantId, long id);
    Optional<Refund> findScopedById(long tenantId, long id, String scopeType, List<Long> teamIds, long selfId);
    List<Refund> search(long tenantId, Long orderId, Long customerId, String status, String scopeType, List<Long> teamIds, long selfId, int offset, int size);
    long countSearch(long tenantId, Long orderId, Long customerId, String status, String scopeType, List<Long> teamIds, long selfId);
    int updateStatus(long tenantId, long id, long version, String fromStatus, String toStatus,
                     Long approvedFen, Long succeededFen, Long approvedBy, Long executedBy, Instant succeededAt);
    void insertItem(RefundItem item);
    List<RefundItem> findItems(long tenantId, long refundId);
    void insertPayment(RefundPayment payment);
    Optional<RefundPayment> findPaymentByExternal(long tenantId, String provider, String externalRefundNo);
    Optional<RefundPayment> findPaymentForUpdate(long tenantId, long id);
    List<RefundPayment> findPayments(long tenantId, long refundId);
    List<RefundPayment> findSucceededPaymentsByOrder(long tenantId, long orderId);
    int updatePaymentStatus(long tenantId, long id, long version, String fromStatus, String toStatus, Instant succeededAt);
    void insertHistory(RefundStatusHistory history);
    List<RefundStatusHistory> findHistory(long tenantId, long refundId);
}
