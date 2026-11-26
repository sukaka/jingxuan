package com.whale.crm.integration.youzan.customer.api.dto;

/**
 * 有赞客户拉取通道与双模式断点游标状态。
 */
public record YouzanCustomerSyncStatusResponse(
        String shopId,
        String adapterName,
        boolean officialEnabled,
        YouzanCustomerCursorSummary scrmCursor,
        YouzanCustomerCursorSummary orderBuyersCursor
) {
}
