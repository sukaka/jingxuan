package com.whale.crm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证安全配置。JWT 密钥与 Cookie 开关全部经环境变量下发；默认值仅供本地开发，禁止用于生产。
 */
@ConfigurationProperties(prefix = "app.security.auth")
public record AuthSecurityProperties(
        String jwtSecret,
        long jwtTtlSeconds,
        String tokenCookieName,
        String csrfCookieName,
        String csrfHeaderName,
        boolean cookieSecure) {
}
