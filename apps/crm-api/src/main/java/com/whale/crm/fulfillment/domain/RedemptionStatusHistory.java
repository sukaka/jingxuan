package com.whale.crm.fulfillment.domain;

import java.time.Instant;

public record RedemptionStatusHistory(Long id, Long redemptionId, String fromStatus, String toStatus,
                                      Long actorUserId, String reason, Instant createdAt) {
}
