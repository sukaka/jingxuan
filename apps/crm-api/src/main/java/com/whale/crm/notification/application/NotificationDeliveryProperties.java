package com.whale.crm.notification.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notification.delivery")
public record NotificationDeliveryProperties(boolean workerEnabled, int batchSize, long leaseMs, int maxRetries,
                                             long retryInitialDelayMs, long retryMaxDelayMs) {
}
