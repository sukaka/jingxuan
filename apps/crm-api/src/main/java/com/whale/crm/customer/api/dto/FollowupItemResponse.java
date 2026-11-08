package com.whale.crm.customer.api.dto;

import java.time.Instant;

/**
 * 跟进列表项（联表客户称呼）。
 */
public record FollowupItemResponse(
        Long id,
        Long customerId,
        String customerName,
        Long ownerStaffId,
        String ownerStaffName,
        String channelCode,
        Instant contactAt,
        String summary,
        String intentProject,
        Long estimatedAmountFen,
        Instant nextFollowupAt,
        String blockerNote,
        Long createdBy,
        Instant createdAt,
        Long version) {
}
