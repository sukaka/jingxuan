package com.whale.crm.ai.domain;

import java.time.Instant;

public record KnowledgeSyncJob(Long id, Long tenantId, Long documentId, Long revisionId, String contentHash,
                               String status, Integer attemptCount, Integer maxAttempts, Instant nextRetryAt,
                               String leaseOwner, Instant leaseExpiresAt, String difyDocumentId, String difyBatch,
                               String lastErrorMasked, Long requestedBy, String idempotencyKey, String requestHash,
                               Instant startedAt, Instant finishedAt, Instant createdAt, Instant updatedAt, Long version) {
}
