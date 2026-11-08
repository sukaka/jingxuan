package com.whale.crm.trading.domain;

import java.time.Instant;

public record EntitlementLedgerEntry(Long id, Long entitlementId, String txnType, Integer deltaCount,
                                     String sourceType, String sourceRef, String idempotencyKey,
                                     Long actorUserId, String reason, Instant createdAt, Long version) {
}
