package com.whale.crm.customer.batchtag.domain;

import java.time.Instant;

public record BatchTagJob(Long id, long tenantId, String jobNo, String previewId, long requestedBy,
                          String operation, String tagCodesJson, String tagSnapshotJson, String sourceType,
                          Long sourceId, String sourceSnapshotJson, String scopeSnapshotJson,
                          String idempotencyKey, Long rollbackOfJobId, String status, int totalCount,
                          int succeededCount, int noChangeCount, int skippedCount, int failedCount,
                          int attemptCount, String leaseOwner, Instant leaseExpiresAt, String lastErrorMasked,
                          Instant createdAt, Instant startedAt, Instant finishedAt, long version) {
    public static final String ADD = "ADD";
    public static final String REMOVE = "REMOVE";
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String FAILED = "FAILED";
}
