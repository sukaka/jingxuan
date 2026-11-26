package com.whale.crm.annualreport.api.dto;

import java.time.Instant;

/**
 * B 端查看有赞历史订单只读快照池与 C 端统一入口概览响应。
 */
public record YouzanAnnualSnapshotSummaryResponse(
        long shopCount,
        long profileCount,
        long linkedCustomerCount,
        long openIdBoundCount,
        long unionIdBoundCount,
        long mobileBoundCount,
        long paidOrderCount,
        long totalPaymentFen,
        long unifiedVisitSessions,
        Instant lastSyncedAt,
        String unifiedEntryUrl,
        boolean wechatMpConfigured,
        boolean smsEnabled
) {
}
