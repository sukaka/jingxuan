package com.whale.crm.trading.domain;

import java.time.Instant;

public record EntitlementCorrectionView(Long ledgerId, Long entitlementId, Long projectId, String txnType,
                                        Integer deltaCount, String sourceRef, String idempotencyKey,
                                        Long actorUserId, String reason, Instant createdAt) {
}
