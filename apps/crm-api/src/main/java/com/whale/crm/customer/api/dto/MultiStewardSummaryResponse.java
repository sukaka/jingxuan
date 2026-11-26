package com.whale.crm.customer.api.dto;

/**
 * 客户唯一性多管家排查统计汇总。
 */
public record MultiStewardSummaryResponse(
        long multiStewardCustomerCount,
        long crossTeamCustomerCount,
        int maxStewardCount) {
}
