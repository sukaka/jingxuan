package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * C 端 H5 微信身份与有赞/CRM 客户持久绑定记录。
 */
public record H5ClientIdentityBinding(
        Long id,
        long tenantId,
        String channelType,
        String openidHash,
        String unionidHash,
        String shopId,
        String yzOpenId,
        Long customerId,
        String mobileHash,
        String boundVia,
        Instant createdAt,
        Instant updatedAt
) {
}
