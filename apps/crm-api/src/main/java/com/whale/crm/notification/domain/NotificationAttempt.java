package com.whale.crm.notification.domain;

import java.time.Instant;

public record NotificationAttempt(Long id, long tenantId, long deliveryId, long notificationId,
                                  int attemptNo, String channel, String status, String provider,
                                  String providerMessageId, String errorMasked, String triggerType,
                                  Long actorUserId, Instant createdAt) {
}
