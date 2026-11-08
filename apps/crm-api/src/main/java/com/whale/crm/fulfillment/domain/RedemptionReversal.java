package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record RedemptionReversal(Long id, Long redemptionId, Long customerId, Long institutionId,
                                 Long ownerStaffId, Long teamId, String status, String reason,
                                 Long requestedBy, Instant requestedAt, Long reviewedBy, Instant reviewedAt,
                                 String reviewReason, String requestIdempotencyKey, String reviewIdempotencyKey,
                                 Long activeRedemptionId, Instant createdAt, Long version) {
}
