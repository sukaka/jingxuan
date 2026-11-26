package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量分配客户归属结果。
 */
public record BatchAssignCustomerOwnerResponse(
        String batchRef,
        Long newOwnerId,
        String newOwnerName,
        Long newTeamId,
        String reason,
        int totalCount,
        int assignedCount,
        int skippedCount,
        Instant effectiveAt,
        List<BatchAssignResultItem> items) {

    public BatchAssignCustomerOwnerResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record BatchAssignResultItem(
            Long customerId,
            String customerNo,
            String customerName,
            String status,
            Long oldOwnerId,
            String oldOwnerName,
            Long newOwnerId,
            String newOwnerName,
            Long historyId,
            Long customerVersion,
            String message) {
    }
}
