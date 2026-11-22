package com.whale.crm.integration.wecom.sidebar.application;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarClient;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.integration.wecom.sidebar.api.dto.WecomJsSdkSignatureResponse.Signature;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WecomJsSdkService {
    private final WecomSidebarClient client;
    private final WecomOfficialProperties officialProperties;
    private final WecomSidebarProperties properties;
    private final StringRedisTemplate redis;

    public WecomJsSdkService(WecomSidebarClient client, WecomOfficialProperties officialProperties,
                             WecomSidebarProperties properties, StringRedisTemplate redis) {
        this.client = client;
        this.officialProperties = officialProperties;
        this.properties = properties;
        this.redis = redis;
    }

    public Signature sign(long tenantId, String url, boolean agentConfig) {
        requireConfigured(tenantId);
        String normalizedUrl = validateUrl(url);
        String ticket = cachedTicket(agentConfig);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long timestamp = Instant.now().getEpochSecond();
        return new Signature(nonce, timestamp, signature(ticket, nonce, timestamp, normalizedUrl));
    }

    public static String signature(String ticket, String nonce, long timestamp, String url) {
        try {
            String raw = "jsapi_ticket=" + ticket + "&noncestr=" + nonce + "&timestamp=" + timestamp + "&url=" + url;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JS-SDK 签名计算失败", ex);
        }
    }

    private String cachedTicket(boolean agentConfig) {
        String key = "wecom:jsapi_ticket:" + officialProperties.corpId() + ":" + (agentConfig ? "agent" : "corp");
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } catch (DataAccessException ex) {
            throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微票据缓存暂不可用");
        }
        synchronized (this) {
            try {
                String cached = redis.opsForValue().get(key);
                if (cached != null && !cached.isBlank()) {
                    return cached;
                }
                WecomSidebarClient.WecomTicket ticket = client.ticket(agentConfig);
                long ttl = Math.max(60, ticket.expiresIn() - Math.max(0, properties.ticketRefreshSkewSeconds()));
                redis.opsForValue().set(key, ticket.ticket(), Duration.ofSeconds(ttl));
                return ticket.ticket();
            } catch (DataAccessException ex) {
                throw new BusinessException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, "企微票据缓存暂不可用");
            }
        }
    }

    private void requireConfigured(long tenantId) {
        if (officialProperties.allowedTenantId() <= 0 || tenantId != officialProperties.allowedTenantId()
                || !properties.enabled() || officialProperties.corpId() == null || officialProperties.corpId().isBlank()
                || properties.agentId() == null || properties.agentId() <= 0) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微侧边栏尚未配置或未启用");
        }
    }

    private String validateUrl(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "签名页面 URL 无效");
        }
        if (uri.getScheme() == null || uri.getHost() == null || uri.getFragment() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "签名 URL 必须是完整页面地址且不包含 #");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = List.of("localhost", "127.0.0.1", "[::1]").contains(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && local)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "非本地签名页面必须使用 HTTPS");
        }
        String origin = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
        boolean trusted = properties.trustedOrigins().stream().map(this::normalizeOrigin).anyMatch(origin::equals);
        if (!trusted) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "签名页面不在企微受信域名范围");
        }
        return value;
    }

    private String normalizeOrigin(String value) {
        return value == null ? "" : value.strip().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }
}
