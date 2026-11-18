package com.whale.crm.customer.batchtag.domain;

import java.time.Instant;

public record BatchTagPreview(String id, long tenantId, long requestedBy, String operation,
                              String tagCodesJson, String tagSnapshotJson, String sourceType, Long sourceId,
                              String sourceSnapshotJson, String scopeSnapshotJson, String idempotencyKey,
                              int memberCount, Instant createdAt) {
}
