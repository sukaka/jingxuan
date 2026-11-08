package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 归属变更历史（只读，不可改）。
 */
public record OwnershipHistoryItemResponse(
        Long id,
        Long customerId,
        Long oldOwnerId,
        String oldOwnerName,
        Long newOwnerId,
        String newOwnerName,
        Long changedBy,
        String changedByName,
        String reason,
        Instant effectiveAt,
        Instant createdAt) {
}
