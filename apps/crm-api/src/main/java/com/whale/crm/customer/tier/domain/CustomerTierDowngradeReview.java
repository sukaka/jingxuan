package com.whale.crm.customer.tier.domain;

import java.time.Instant;

public record CustomerTierDowngradeReview(Long id, long tenantId, long customerId, long ruleId, long snapshotId,
                                          String fromTierCode, String toTierCode, long baseCustomerVersion,
                                          String status, Long requestedBy, Long reviewedBy, String reviewReason,
                                          Instant reviewedAt, Instant effectiveAt, Instant createdAt,
                                          Instant updatedAt, long version) {
    public static final String PENDING = "PENDING_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String STALE = "STALE";
}
