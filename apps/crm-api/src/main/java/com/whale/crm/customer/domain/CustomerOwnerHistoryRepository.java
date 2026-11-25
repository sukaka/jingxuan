package com.whale.crm.customer.domain;

import java.time.Instant;
import java.util.List;

/**
 * crm_customer_owner_history 存取。只追加与查询，永不更新/删除。
 */
public interface CustomerOwnerHistoryRepository {

    List<CustomerOwnerHistory> findByCustomerId(long tenantId, long customerId);

    void insert(CustomerOwnerHistory history);

    /**
     * 联表用户名投影（展示用）。
     */
    List<OwnerHistoryRow> findRowsByCustomerId(long tenantId, long customerId);

    List<OwnerHistoryRow> findTimelineRowsByCustomerId(long tenantId, long customerId, String cursorMode,
                                                       Instant cursorOccurredAt, Long cursorSourceId, int limit);

    record OwnerHistoryRow(Long id, Long customerId, Long oldOwnerId, String oldOwnerName,
                           Long newOwnerId, String newOwnerName, Long changedBy, String changedByName,
                           String reason, java.time.Instant effectiveAt, java.time.Instant createdAt) {
    }
}
