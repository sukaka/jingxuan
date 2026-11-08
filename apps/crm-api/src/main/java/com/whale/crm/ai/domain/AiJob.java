package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiJob(Long id, Long tenantId, Long customerId, String conversationId, Long requestedBy,
                    String messageIdsJson, String requestHash, String idempotencyKey, String status,
                    String claimedBy, Instant claimedUntil, Integer attemptCount, Instant nextRetryAt,
                    String provider, String modelName, String errorMasked, Long confirmedBy,
                    Instant confirmedAt, String confirmationReason, String confirmationKey,
                    String confirmationHash, Long taskId,
                    Instant createdAt, Instant updatedAt, Long version) {
    public boolean terminal() {
        return "PROPOSED".equals(status) || "FAILED".equals(status) || "CONFIRMED".equals(status) || "REJECTED".equals(status);
    }
}
