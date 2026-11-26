package com.whale.crm.annualreport.api.dto;

import java.time.Instant;

/**
 * B 端年终报告列表及单条详情响应（含实时访问统计）。
 */
public record AnnualReportItemResponse(
        long id,
        long customerId,
        long ownerStaffId,
        int reportYear,
        String shareKey,
        String link,
        String toneCode,
        String toneReason,
        String displayName,
        String tierLabel,
        String butlerName,
        int orderCount,
        long netSpendFen,
        int redeemCount,
        int remainingEntitlementCount,
        String status,
        Instant createdAt,
        long version,
        long visitCount,
        long totalVisibleDurationMs,
        int maxSceneReached,
        int maxViewedSceneCount,
        boolean generatedPoster,
        boolean clickedBookButler,
        Instant lastActiveAt
) {
}
