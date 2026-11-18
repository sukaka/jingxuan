package com.whale.crm.customer.tier.api.dto;

import java.time.Instant;

public record CustomerTierHistoryResponse(long id, long customerId, Long ruleId, Long snapshotId, Long reviewId,
                                          String fromTierCode, String toTierCode, String changeType,
                                          Long actorUserId, String reason, Instant effectiveAt, Instant createdAt) {
}
