package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * 有赞历史订单只读快照（仅用于年终 H5 轨迹展示，严禁发放权益或覆盖 crm_order）。
 */
public record YouzanAnnualOrderSnapshot(
        Long id,
        long tenantId,
        String shopId,
        String tid,
        String yzOpenId,
        String orderStatus,
        boolean paid,
        Instant orderedAt,
        Instant paidAt,
        long paymentFen,
        long refundedFen,
        String titleSummary,
        String itemsJson,
        Instant syncedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
