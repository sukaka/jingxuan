package com.whale.crm.customer.tier.domain;

import java.time.Instant;

public record CustomerTierHistory(Long id, long tenantId, long customerId, Long ruleId, Long snapshotId,
                                  Long reviewId, String fromTierCode, String toTierCode, String changeType,
                                  Long actorUserId, String reason, Instant effectiveAt, Instant createdAt) {
}
