package com.whale.crm.trading.domain;

import java.time.Instant;

public record OutboxEvent(Long id, Long tenantId, String eventId, String eventType, Integer eventVersion,
                          String aggregateType, String aggregateId, String partitionKey,
                          String payloadJson, String status, Integer retryCount, Instant sentAt,
                          String lastError, Instant nextRetryAt, String claimOwner, Instant claimExpiresAt,
                          Instant createdAt, Long version) {
}
