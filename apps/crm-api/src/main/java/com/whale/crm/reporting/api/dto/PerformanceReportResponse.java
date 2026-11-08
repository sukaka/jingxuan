package com.whale.crm.reporting.api.dto;

import com.whale.crm.reporting.domain.ReportFact;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PerformanceReportResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                        String timeZone, String sourceSystem, String receiverType,
                                        String metricVersion, Instant sourceWatermark, String performanceRole,
                                        PerformanceTotals totals, List<PerformanceRow> items,
                                        List<DailyItem> dailyItems, long total, int page, int size,
                                        String targetStatus, String commissionStatus, String usageStatus,
                                        Long teamTargetFen, String teamTargetStatus,
                                        BigDecimal teamTargetCompletionRate, String teamTargetCompletionStatus,
                                        List<ReportFact> drillDown, String drillDownType, int drillDownLimit) {
    public PerformanceReportResponse {
        items = items == null ? null : List.copyOf(items);
        dailyItems = dailyItems == null ? null : List.copyOf(dailyItems);
        drillDown = drillDown == null ? null : List.copyOf(drillDown);
    }

    public record PerformanceTotals(long paidOrderCount, long refundCount, long paidFen,
                                    long platformPaidFen, long institutionPaidFen, long refundFen,
                                    long platformRefundFen, long institutionRefundFen, long netPaidFen,
                                    long platformNetPaidFen, long institutionNetPaidFen) {
    }

    public record DailyItem(LocalDate statDate, long paidOrderCount, long refundCount,
                            long paidFen, long refundFen, long netPaidFen) {
    }

    public record PerformanceRow(Long userId, String displayName, String attributionStatus,
                                 long paidOrderCount, long refundCount, long paidFen,
                                 long platformPaidFen, long institutionPaidFen, long refundFen,
                                 long platformRefundFen, long institutionRefundFen, long netPaidFen,
                                 long platformNetPaidFen, long institutionNetPaidFen, Long targetFen,
                                 String targetStatus, BigDecimal targetCompletionRate,
                                 String targetCompletionStatus, Long commissionFen, String commissionStatus,
                                 Instant sourceWatermark) {
    }
}
