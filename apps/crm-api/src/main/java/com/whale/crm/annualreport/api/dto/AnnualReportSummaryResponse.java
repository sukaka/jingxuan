package com.whale.crm.annualreport.api.dto;

import java.util.List;

/**
 * B 端年终 H5 运营统计与逐幕留存漏斗响应（受后端数据范围隔离）。
 */
public record AnnualReportSummaryResponse(
        int reportYear,
        long totalReports,
        long activeReports,
        long openedReports,
        long completedReports,
        long totalSessions,
        long avgVisibleDurationMs,
        long posterReports,
        long bookingIntentReports,
        List<SceneFunnelItem> sceneFunnel
) {
    public AnnualReportSummaryResponse {
        sceneFunnel = sceneFunnel == null ? List.of() : List.copyOf(sceneFunnel);
    }

    public record SceneFunnelItem(int sceneIndex, String label, long reachedReports) {
    }
}
