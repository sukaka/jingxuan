package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * 有赞买家年度只读档案镜像（不含明文手机号与明文 OpenID）。
 */
public record YouzanAnnualProfile(
        Long id,
        long tenantId,
        String shopId,
        String yzOpenId,
        Long customerId,
        String displayName,
        String genderHint,
        String weixinOpenIdHash,
        String weixinUnionIdHash,
        String mobileHash,
        String mobileMasked,
        int orderCount,
        long totalPaymentFen,
        Instant firstOrderAt,
        Instant lastOrderAt,
        Instant syncedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
