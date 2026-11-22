package com.whale.crm.notification.infrastructure.wecom;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.wecom.message")
public record WecomMessageProperties(long allowedTenantId, String mode, String corpId, String secret,
                                     Long agentId, String apiBaseUrl, int connectTimeoutMs,
                                     int readTimeoutMs, int tokenRefreshSkewSeconds) {

    @ConstructorBinding
    public WecomMessageProperties {
    }

    public WecomMessageProperties(String mode, String corpId, String secret, Long agentId, String apiBaseUrl,
                                  int connectTimeoutMs, int readTimeoutMs, int tokenRefreshSkewSeconds) {
        this(1L, mode, corpId, secret, agentId, apiBaseUrl, connectTimeoutMs, readTimeoutMs,
                tokenRefreshSkewSeconds);
    }
}
