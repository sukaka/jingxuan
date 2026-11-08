package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record Redemption(Long id, Long appointmentId, Long customerId, Long institutionId,
                         Long ownerStaffId, Long teamId, Instant serviceAt, String evidenceRef,
                         Long voucherFileId, String status, String settlementStatus,
                         String idempotencyKey, Long postedBy, Instant postedAt,
                         Instant createdAt, Long version) {
}
