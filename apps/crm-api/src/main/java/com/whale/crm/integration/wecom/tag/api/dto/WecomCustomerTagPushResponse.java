package com.whale.crm.integration.wecom.tag.api.dto;

/**
 * 单客户标签手动同步至企微的结果响应。
 */
public record WecomCustomerTagPushResponse(
        long customerId,
        String adapterName,
        boolean officialEnabled,
        int matchedContacts,
        int pushedCount,
        int skippedCount,
        int failedCount,
        String errorMasked) {
}
