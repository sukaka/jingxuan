package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 转交审批单视图（含联表姓名）。
 */
public record TransferItemResponse(
        Long id,
        Long customerId,
        Long fromOwnerId,
        String fromOwnerName,
        Long toOwnerId,
        String toOwnerName,
        Long fromTeamId,
        Long toTeamId,
        String status,
        Long requestedBy,
        String requestedByName,
        String reason,
        Long reviewedBy,
        String reviewedByName,
        Instant reviewedAt,
        String reviewReason,
        Long baseVersion,
        Instant effectiveAt,
        Instant createdAt,
        Long version) {
}
