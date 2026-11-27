package com.whale.crm.annualreport.api.dto;

import com.whale.crm.platform.web.PageResponse;

import java.time.Instant;
import java.util.List;

/**
 * 客户详情页「有赞历史订单（只读快照）」响应：
 * 仅展示只读历史消费轨迹，严禁计入 CRM 正式订单账本或触发会员权益发放，且不包含任何明文手机号/OpenID/地址。
 */
public record CustomerYouzanHistoryOrdersResponse(
        long customerId,
        boolean hasYouzanIdentity,
        List<String> linkedShopIds,
        Summary summary,
        PageResponse<OrderItem> orders
) {
    public CustomerYouzanHistoryOrdersResponse {
        linkedShopIds = linkedShopIds == null ? List.of() : List.copyOf(linkedShopIds);
    }

    public record Summary(
            int totalOrderCount,
            int paidOrderCount,
            long totalPaymentFen,
            long totalRefundedFen,
            long netSpendFen,
            Instant firstOrderAt,
            Instant lastOrderAt,
            Instant lastSyncedAt
    ) {
        public static Summary empty() {
            return new Summary(0, 0, 0L, 0L, 0L, null, null, null);
        }
    }

    public record OrderItem(
            Long id,
            String shopId,
            String tid,
            String orderStatus,
            boolean paid,
            Instant orderedAt,
            Instant paidAt,
            long paymentFen,
            long refundedFen,
            long netPaymentFen,
            String titleSummary,
            List<OrderLineItem> items,
            Instant syncedAt
    ) {
        public OrderItem {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record OrderLineItem(
            String itemId,
            String skuId,
            String title,
            String skuProps,
            int num,
            long priceFen
    ) {
    }
}
