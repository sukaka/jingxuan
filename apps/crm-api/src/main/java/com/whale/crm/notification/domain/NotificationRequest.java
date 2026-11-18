package com.whale.crm.notification.domain;

import java.util.Set;

public record NotificationRequest(long tenantId, long recipientUserId, String notificationType,
                                  String title, String contentMasked, String sourceType, String sourceId,
                                  String dedupeKey, String deepLink, Set<NotificationChannel> channels) {
    public NotificationRequest {
        channels = channels == null ? null : Set.copyOf(channels);
    }
}
