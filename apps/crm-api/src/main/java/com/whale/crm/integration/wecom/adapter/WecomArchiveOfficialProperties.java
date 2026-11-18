package com.whale.crm.integration.wecom.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.wecom.archive")
public record WecomArchiveOfficialProperties(long allowedTenantId, String corpId, String secret,
                                             String sdkLibraryPath, String privateKeyDirectory,
                                             String proxy, String proxyPassword, int timeoutSeconds) {
}
