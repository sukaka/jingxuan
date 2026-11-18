package com.whale.crm.notification.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wecom.message")
public record WecomMessageProperties(String mode, String corpId, String secret, Long agentId, String apiBaseUrl,
                                     int connectTimeoutMs, int readTimeoutMs, int tokenRefreshSkewSeconds) {
}
