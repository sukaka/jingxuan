package com.whale.crm.followup.domain;

import java.time.Instant;

public record FollowupBatchItem(Long id, long tenantId, long batchId, long customerId, String customerName,
                                long ownerStaffId, Long teamId, Instant dueAt, Long taskId, String status,
                                String reason, long version) {
    public static final String PENDING = "PENDING";
    public static final String CREATED = "CREATED";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
}
