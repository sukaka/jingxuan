package com.whale.crm.integration.wecom.tag.api.dto;

import java.util.List;

/**
 * 企微标签映射总览（含当前租户企微模式、适配器名称与全部 CRM 客户标签映射状态）。
 */
public record WecomTagMappingOverviewResponse(
        String corpId,
        String adapterName,
        boolean officialEnabled,
        List<WecomTagMappingItemResponse> items) {

    public WecomTagMappingOverviewResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
