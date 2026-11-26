package com.whale.crm.integration.youzan.customer.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 有赞客户拉取逐条脱敏结果响应（绝不包含明文手机号与明文微信 OpenID）。
 */
public record YouzanCustomerSyncItemResponse(
        Long id,
        long runId,
        String shopId,
        String externalCustomerId,
        String outcome,
        String displayNameMasked,
        String mobileMasked,
        Long crmCustomerId,
        String crmCustomerNo,
        List<Long> conflictCustomerIds,
        List<String> conflictCustomerNos,
        String detailMasked,
        Instant createdAt
) {
    public YouzanCustomerSyncItemResponse {
        conflictCustomerIds = conflictCustomerIds == null ? List.of() : List.copyOf(conflictCustomerIds);
        conflictCustomerNos = conflictCustomerNos == null ? List.of() : List.copyOf(conflictCustomerNos);
    }
}
