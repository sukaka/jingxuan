package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 客户归属日志（全局分页视角，覆盖主管直接分配、批量分配与转交审批生效记录）。
 */
public record CustomerOwnershipLogItemResponse(
        Long id,
        Long customerId,
        String customerNo,
        String customerName,
        Long oldOwnerId,
        String oldOwnerName,
        Long newOwnerId,
        String newOwnerName,
        Long changedBy,
        String changedByName,
        String reason,
        String approvalRef,
        String changeMode,
        Instant effectiveAt,
        Instant createdAt) {
}
