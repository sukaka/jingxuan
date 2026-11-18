package com.whale.crm.integration.wecom.media.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wecom.media")
public record WecomMediaProperties(boolean workerEnabled, int batchSize, long leaseMs, int maxAttempts,
                                   long maxFileBytes, int maxChunks, long delayMs) {
}
