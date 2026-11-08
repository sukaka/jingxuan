package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanSyncIssue(Long id, Long eventId, String issueType, String status, String detailMasked,
                              Integer attempts, String lastError, Instant nextRetryAt, Instant createdAt,
                              Instant resolvedAt, Long resolvedBy, Long version) {
}
