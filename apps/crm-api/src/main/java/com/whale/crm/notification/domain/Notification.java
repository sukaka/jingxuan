package com.whale.crm.notification.domain;

import java.time.Instant;

public record Notification(Long id, long tenantId, long recipientUserId, String notificationType,
                           String title, String contentMasked, String sourceType, String sourceId,
                           String dedupeKey, String deepLink, String channels, Instant createdAt,
                           Instant readAt, Long version) {
}
