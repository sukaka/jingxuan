package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 单客直接分配归属结果。
 */
public record CustomerOwnerAssignmentResponse(
        Long historyId,
        Long customerId,
        String customerNo,
        String customerName,
        Long oldOwnerId,
        String oldOwnerName,
        Long newOwnerId,
        String newOwnerName,
        Long oldTeamId,
        Long newTeamId,
        Long changedBy,
        String changedByName,
        String reason,
        String approvalRef,
        Instant effectiveAt,
        Long customerVersion) {
}
