package com.whale.crm.customer.api.dto;

/**
 * 客户来源身份（详情用）。externalId 只返回掩码形态。
 */
public record CustomerIdentityItemResponse(
        String identityType,
        String sourceApp,
        String externalIdMasked,
        String status) {
}
