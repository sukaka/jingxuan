package com.whale.crm.notification.domain;

import java.time.Instant;

public record NotificationDelivery(Long id, long tenantId, long notificationId, long recipientUserId,
                                   String channel, String status, int attemptCount, int automaticRetryCount,
                                   Instant nextAttemptAt, String claimOwner, Instant claimExpiresAt,
                                   Instant lastStartedAt, String triggerType, Long triggerActorUserId,
                                   String provider, String providerMessageId,
                                   String lastErrorMasked, Instant createdAt, Instant updatedAt, Long version) {
}
