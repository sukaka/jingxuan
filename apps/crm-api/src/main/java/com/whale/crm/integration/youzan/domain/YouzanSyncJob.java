package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanSyncJob(Long id, String shopId, String jobType, String status, String cursorValue,
                            Instant windowStart, Instant windowEnd, Integer receivedCount, Integer processedCount,
                            Integer duplicateCount, Integer issueCount, String lastError, String leaseOwner,
                            Instant leaseExpiresAt, Instant createdAt, Instant startedAt, Instant finishedAt,
                            Long version) {
}
