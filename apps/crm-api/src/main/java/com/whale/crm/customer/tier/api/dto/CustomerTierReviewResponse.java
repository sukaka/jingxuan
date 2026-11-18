package com.whale.crm.customer.tier.api.dto;

import java.time.Instant;

public record CustomerTierReviewResponse(long id, long customerId, long ruleId, long snapshotId,
                                         String fromTierCode, String toTierCode, long baseCustomerVersion,
                                         String status, Long requestedBy, Long reviewedBy, String reviewReason,
                                         Instant reviewedAt, Instant effectiveAt, Instant createdAt, long version) {
}
