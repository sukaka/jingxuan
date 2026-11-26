package com.whale.crm.annualreport.api.dto;

import java.time.Instant;

/**
 * B 端查看单个有赞买家只读档案列表项（严格脱敏，无明文手机号与明文 OpenID）。
 */
public record YouzanAnnualProfileItemResponse(
        long id,
        String shopId,
        String yzOpenIdMasked,
        Long customerId,
        String displayName,
        String genderHint,
        String mobileMasked,
        boolean hasWechatOpenId,
        boolean hasWechatUnionId,
        int orderCount,
        long totalPaymentFen,
        Instant firstOrderAt,
        Instant lastOrderAt,
        Instant syncedAt
) {
}
