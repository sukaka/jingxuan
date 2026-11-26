package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * C 端年终 H5 手机号验证码校验记录（仅存盲索引与验证码哈希）。
 */
public record H5SmsVerification(
        Long id,
        long tenantId,
        String mobileHash,
        String codeHash,
        int attemptCount,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
}
