package com.whale.crm.integration.youzan.customer.domain;

import java.time.Instant;

/**
 * 有赞客户拉取建档逐条脱敏处理结果明细。
 */
public record YouzanCustomerSyncItem(
        Long id,
        long tenantId,
        long runId,
        String shopId,
        String externalCustomerId,
        String outcome,
        String displayNameMasked,
        String mobileMasked,
        Long crmCustomerId,
        String crmCustomerNo,
        String conflictCustomerIdsJson,
        String conflictCustomerNosJson,
        String detailMasked,
        Instant createdAt
) {
}
