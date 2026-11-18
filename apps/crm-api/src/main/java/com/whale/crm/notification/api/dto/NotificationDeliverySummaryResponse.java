package com.whale.crm.notification.api.dto;

import com.whale.crm.notification.domain.NotificationDelivery;

public record NotificationDeliverySummaryResponse(String channel, String status, String lastErrorMasked,
                                                   String providerMessageId) {
    public static NotificationDeliverySummaryResponse from(NotificationDelivery delivery) {
        return new NotificationDeliverySummaryResponse(delivery.channel(), delivery.status(),
                delivery.lastErrorMasked(), delivery.providerMessageId());
    }
}
