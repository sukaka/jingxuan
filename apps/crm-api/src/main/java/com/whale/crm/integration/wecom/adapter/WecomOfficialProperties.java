package com.whale.crm.integration.wecom.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.wecom.official")
public record WecomOfficialProperties(long allowedTenantId, boolean enabled, String corpId, String secret,
                                      String apiBaseUrl, String callbackToken, String callbackAesKey,
                                      int connectTimeoutMs, int readTimeoutMs, int pageSize, int maxPages) {

    @ConstructorBinding
    public WecomOfficialProperties {
    }

    public WecomOfficialProperties(boolean enabled, String corpId, String secret, String apiBaseUrl,
                                   String callbackToken, String callbackAesKey, int connectTimeoutMs,
                                   int readTimeoutMs, int pageSize, int maxPages) {
        this(1L, enabled, corpId, secret, apiBaseUrl, callbackToken, callbackAesKey,
                connectTimeoutMs, readTimeoutMs, pageSize, maxPages);
    }
}
