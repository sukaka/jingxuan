package com.whale.crm.notification.api.dto;

import com.whale.crm.notification.domain.NotificationDelivery;

import java.time.Instant;

public record NotificationDeliveryResponse(long id, long notificationId, long recipientUserId, String channel,
                                           String status, int attemptCount, int automaticRetryCount,
                                           Instant nextAttemptAt, Instant lastStartedAt, String provider,
                                           String providerMessageId, String lastErrorMasked,
                                           Instant createdAt, Instant updatedAt, long version) {
    public static NotificationDeliveryResponse from(NotificationDelivery delivery) {
        return new NotificationDeliveryResponse(delivery.id(), delivery.notificationId(), delivery.recipientUserId(),
                delivery.channel(), delivery.status(), delivery.attemptCount(), delivery.automaticRetryCount(),
                delivery.nextAttemptAt(), delivery.lastStartedAt(), delivery.provider(), delivery.providerMessageId(),
                delivery.lastErrorMasked(), delivery.createdAt(), delivery.updatedAt(), delivery.version());
    }
}
