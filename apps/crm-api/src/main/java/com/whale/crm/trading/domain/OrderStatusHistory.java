package com.whale.crm.trading.domain;

import java.time.Instant;

public record OrderStatusHistory(Long id, Long orderId, String fromStatus, String toStatus,
                                 Long actorUserId, String triggerType, String triggerRef,
                                 String reason, Instant createdAt) {
}
