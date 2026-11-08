package com.whale.crm.integration.wecom.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wecom.official")
public record WecomOfficialProperties(boolean enabled, String corpId, String secret, String apiBaseUrl,
                                      String callbackToken, String callbackAesKey, int connectTimeoutMs,
                                      int readTimeoutMs, int pageSize, int maxPages) {
}
