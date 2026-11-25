package com.whale.crm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 密码复杂度策略配置。通过环境变量或 application.yml 统一管理，前端通过 /api/v1/auth/password-policy 获取同源规则。
 */
@ConfigurationProperties(prefix = "app.security.auth.password-policy")
public record PasswordPolicyProperties(
        @DefaultValue("8") int minLength,
        @DefaultValue("64") int maxLength,
        @DefaultValue("true") boolean requireUppercase,
        @DefaultValue("true") boolean requireLowercase,
        @DefaultValue("true") boolean requireDigit,
        @DefaultValue("true") boolean requireSpecial,
        @DefaultValue("true") boolean forbidWhitespace,
        @DefaultValue("true") boolean forbidUsername,
        @DefaultValue("true") boolean forbidSameAsCurrent) {

    public static final int MIN_ALLOWED_LENGTH = 8;
    public static final int MAX_ALLOWED_LENGTH = 128;

    public PasswordPolicyProperties {
        int safeMin = Math.max(MIN_ALLOWED_LENGTH, Math.min(minLength, MAX_ALLOWED_LENGTH));
        int safeMax = Math.max(safeMin, Math.min(maxLength, MAX_ALLOWED_LENGTH));
        minLength = safeMin;
        maxLength = safeMax;
    }

    public static PasswordPolicyProperties defaultPolicy() {
        return new PasswordPolicyProperties(8, 64, true, true, true, true, true, true, true);
    }
}
