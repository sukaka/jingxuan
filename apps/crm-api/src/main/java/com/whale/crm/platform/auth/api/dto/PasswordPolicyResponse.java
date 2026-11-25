package com.whale.crm.platform.auth.api.dto;

/**
 * 密码复杂度策略响应（前端修改密码弹窗用于展示与同步预校验，最终以服务端校验为准）。
 */
public record PasswordPolicyResponse(
        int minLength,
        int maxLength,
        boolean requireUppercase,
        boolean requireLowercase,
        boolean requireDigit,
        boolean requireSpecial,
        boolean forbidWhitespace,
        boolean forbidUsername,
        boolean forbidSameAsCurrent) {
}
