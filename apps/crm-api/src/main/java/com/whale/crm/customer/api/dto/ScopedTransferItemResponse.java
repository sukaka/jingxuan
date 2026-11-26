package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 客户转交审批单列表项（含客户编号与名称，用于归属日志页分页查询）。
 */
public record ScopedTransferItemResponse(
        Long id,
        Long customerId,
        String customerNo,
        String customerName,
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
