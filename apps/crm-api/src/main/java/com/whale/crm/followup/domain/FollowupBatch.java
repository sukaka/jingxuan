package com.whale.crm.followup.domain;

import java.time.Instant;

public record FollowupBatch(Long id, long tenantId, String orderMonth, String status, long createdBy,
                            int retryCount, Instant nextRetryAt, String leaseOwner, Instant leaseExpiresAt,
                            String lastErrorMasked, long version) {
    public static final String READY = "READY";
    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
}
