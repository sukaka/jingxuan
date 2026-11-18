package com.whale.crm.integration.wecom.sidebar.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.wecom.sidebar")
public record WecomSidebarProperties(boolean enabled, Long agentId, String oauthAuthorizeUrl,
                                     String oauthRedirectUri, String sidebarUrl, List<String> trustedOrigins,
                                     long oauthStateTtlSeconds, long ticketRefreshSkewSeconds) {
    public WecomSidebarProperties {
        trustedOrigins = trustedOrigins == null ? List.of() : List.copyOf(trustedOrigins);
    }
}
