package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record RedemptionReversalHistory(Long id, Long reversalId, String fromStatus, String toStatus,
                                        Long actorUserId, String reason, Instant createdAt) {
}
