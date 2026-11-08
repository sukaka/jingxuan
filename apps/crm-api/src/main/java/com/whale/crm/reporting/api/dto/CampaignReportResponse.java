package com.whale.crm.reporting.api.dto;

import com.whale.crm.reporting.domain.ReportFact;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record CampaignReportResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                     String timeZone, String sourceSystem, String receiverType,
                                     String metricVersion, Instant sourceWatermark, CampaignTotals totals,
                                     List<CampaignRow> items, long total, int page, int size,
                                     List<ReportFact> drillDown, String drillDownType, int drillDownLimit) {
    public CampaignReportResponse {
        items = items == null ? null : List.copyOf(items);
        drillDown = drillDown == null ? null : List.copyOf(drillDown);
    }

    public record CampaignTotals(long paidOrderCount, long refundCount, long confirmedAppointmentCount,
                                 long checkedInCustomerCount, long checkedInAppointmentCount,
                                 long redemptionBatchCount, long redeemedCount, long paidFen,
                                 long platformPaidFen, long institutionPaidFen, long refundFen,
                                 long platformRefundFen, long institutionRefundFen, long netPaidFen,
                                 long platformNetPaidFen, long institutionNetPaidFen,
                                 Long touchCustomerCount, String touchStatus) {
    }

    public record CampaignRow(Long campaignId, Long campaignBatchId, String campaignCode,
                              String campaignBatchCode, String campaignName, String campaignBatchName,
                              long paidOrderCount, long refundCount, long confirmedAppointmentCount,
                              long checkedInCustomerCount, long checkedInAppointmentCount,
                              long redemptionBatchCount, long redeemedCount, long paidFen,
                              long platformPaidFen, long institutionPaidFen, long refundFen,
                              long platformRefundFen, long institutionRefundFen, long netPaidFen,
                              long platformNetPaidFen, long institutionNetPaidFen,
                              Long touchCustomerCount, String touchStatus,
                              Map<String, PendingRatio> conversionRates, Instant sourceWatermark) {
        public CampaignRow {
            conversionRates = conversionRates == null ? null : Map.copyOf(conversionRates);
        }
    }
}
