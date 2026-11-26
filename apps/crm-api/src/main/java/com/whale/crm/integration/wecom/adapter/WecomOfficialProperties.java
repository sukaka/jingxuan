package com.whale.crm.integration.wecom.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "app.wecom.official")
public record WecomOfficialProperties(long allowedTenantId, boolean enabled, String corpId, String secret,
                                      String apiBaseUrl, String callbackToken, String callbackAesKey,
                                      int connectTimeoutMs, int readTimeoutMs, int pageSize, int maxPages,
                                      String defaultTagGroupName) {

    public static final String DEFAULT_TAG_GROUP_NAME = "鲸选CRM标签";

    @ConstructorBinding
    public WecomOfficialProperties {
        defaultTagGroupName = defaultTagGroupName == null || defaultTagGroupName.isBlank()
                ? DEFAULT_TAG_GROUP_NAME : defaultTagGroupName.strip();
    }

    public WecomOfficialProperties(long allowedTenantId, boolean enabled, String corpId, String secret,
                                   String apiBaseUrl, String callbackToken, String callbackAesKey,
                                   int connectTimeoutMs, int readTimeoutMs, int pageSize, int maxPages) {
        this(allowedTenantId, enabled, corpId, secret, apiBaseUrl, callbackToken, callbackAesKey,
                connectTimeoutMs, readTimeoutMs, pageSize, maxPages, DEFAULT_TAG_GROUP_NAME);
    }

    public WecomOfficialProperties(boolean enabled, String corpId, String secret, String apiBaseUrl,
                                   String callbackToken, String callbackAesKey, int connectTimeoutMs,
                                   int readTimeoutMs, int pageSize, int maxPages) {
        this(1L, enabled, corpId, secret, apiBaseUrl, callbackToken, callbackAesKey,
                connectTimeoutMs, readTimeoutMs, pageSize, maxPages, DEFAULT_TAG_GROUP_NAME);
    }
}
