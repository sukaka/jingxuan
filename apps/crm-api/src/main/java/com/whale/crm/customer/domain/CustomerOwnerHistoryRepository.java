package com.whale.crm.customer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * crm_customer_owner_history 存取。只追加与查询，永不更新/删除。
 */
public interface CustomerOwnerHistoryRepository {

    List<CustomerOwnerHistory> findByCustomerId(long tenantId, long customerId);

    void insert(CustomerOwnerHistory history);

    Optional<OwnerHistoryRow> findRowById(long tenantId, long id);

    List<OwnerHistoryRow> findRowsByApprovalRef(long tenantId, String approvalRef);

    /**
     * 联表用户名投影（展示用）。
     */
    List<OwnerHistoryRow> findRowsByCustomerId(long tenantId, long customerId);

    List<OwnerHistoryRow> findTimelineRowsByCustomerId(long tenantId, long customerId, String cursorMode,
                                                       Instant cursorOccurredAt, Long cursorSourceId, int limit);

    List<OwnerHistoryLogRow> searchLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                        Long customerId, Long ownerId, String keyword, int offset, int size);

    long countSearchLogs(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                         Long customerId, Long ownerId, String keyword);

    record OwnerHistoryRow(Long id, Long customerId, Long oldOwnerId, String oldOwnerName,
                           Long newOwnerId, String newOwnerName, Long changedBy, String changedByName,
                           String reason, String approvalRef, java.time.Instant effectiveAt,
                           java.time.Instant createdAt) {
    }

    record OwnerHistoryLogRow(Long id, Long customerId, String customerNo, String customerName,
                              Long oldOwnerId, String oldOwnerName, Long newOwnerId, String newOwnerName,
                              Long changedBy, String changedByName, String reason, String approvalRef,
                              java.time.Instant effectiveAt, java.time.Instant createdAt) {
    }
}
