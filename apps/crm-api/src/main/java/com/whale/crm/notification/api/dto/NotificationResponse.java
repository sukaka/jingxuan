package com.whale.crm.notification.api.dto;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationDelivery;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public record NotificationResponse(long id, long tenantId, long recipientUserId, String notificationType,
                                   String title, String contentMasked, String sourceType, String sourceId,
                                   String dedupeKey, String deepLink, List<String> channels,
                                   List<NotificationDeliverySummaryResponse> deliveries,
                                   Instant createdAt, Instant readAt, long version) {
    public NotificationResponse {
        channels = channels == null ? null : List.copyOf(channels);
        deliveries = deliveries == null ? null : List.copyOf(deliveries);
    }

    public static NotificationResponse from(Notification notification, List<NotificationDelivery> deliveries) {
        return new NotificationResponse(notification.id(), notification.tenantId(), notification.recipientUserId(),
                notification.notificationType(), notification.title(), notification.contentMasked(),
                notification.sourceType(), notification.sourceId(), notification.dedupeKey(), notification.deepLink(),
                Arrays.stream(notification.channels().split(",")).toList(),
                deliveries.stream().map(NotificationDeliverySummaryResponse::from).toList(), notification.createdAt(),
                notification.readAt(), notification.version());
    }
}
