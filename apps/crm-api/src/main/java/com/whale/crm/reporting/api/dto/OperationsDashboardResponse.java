package com.whale.crm.reporting.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record OperationsDashboardResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                          String timeZone, String sourceSystem, String receiverType,
                                          String metricVersion, Instant sourceWatermark,
                                          OperationsReportResponse.OperationsMetrics metrics,
                                          List<PerformanceReportResponse.DailyItem> dailyItems,
                                          List<OperationsReportResponse.InstitutionRow> institutionRows,
                                          List<CampaignReportResponse.CampaignRow> campaignRows,
                                          OperationsDutyResponse duty) {
    public OperationsDashboardResponse {
        dailyItems = dailyItems == null ? null : List.copyOf(dailyItems);
        institutionRows = institutionRows == null ? null : List.copyOf(institutionRows);
        campaignRows = campaignRows == null ? null : List.copyOf(campaignRows);
    }
}
