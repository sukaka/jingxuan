package com.whale.crm.reporting.domain;

import java.time.Instant;
import java.util.List;

public interface ReportingRepository {
    OperationsFactRow operations(ReportQuery query);

    long countNewCustomers(ReportQuery query);

    List<OperationsStaffFactRow> operationsStaff(ReportQuery query);

    List<OperationsInstitutionFactRow> operationsInstitutions(ReportQuery query);

    List<PerformanceFactRow> performance(ReportQuery query, String performanceRole);

    List<DailyFactRow> dailyFacts(ReportQuery query, String performanceRole);

    List<ApprovedTargetRow> approvedTargets(ReportQuery query, String performanceRole, String periodKey,
                                            List<Long> staffIds);

    List<CampaignFactRow> campaigns(ReportQuery query);

    CampaignFactRow campaignTotals(ReportQuery query);

    List<SkuQualityFactRow> skuQuality(ReportQuery query);

    long countSku(ReportQuery query);

    Instant skuSourceWatermark(ReportQuery query);

    List<ReportFact> operationsDrillDown(ReportQuery query, String factType, int limit);

    List<ReportFact> performanceDrillDown(ReportQuery query, String performanceRole, String factType, int limit);

    List<ReportFact> campaignDrillDown(ReportQuery query, String factType, int limit);

    List<ReportFact> skuDrillDown(ReportQuery query, String factType, int limit);

    record OperationsFactRow(long contactCustomerCount, long contactEventCount, long paidOrderCount,
                             long confirmedAppointmentCount, long checkedInCustomerCount,
                             long checkedInAppointmentCount, long serviceCompletedCount,
                             long redemptionBatchCount, long redeemedCustomerCount, long redeemedCount,
                             long refundCount, long paidFen, long platformPaidFen, long institutionPaidFen,
                             long refundFen, long platformRefundFen, long institutionRefundFen,
                             Instant sourceWatermark) {
        public static OperationsFactRow empty() {
            return new OperationsFactRow(0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    record OperationsStaffFactRow(Long staffUserId, String staffName, long contactCustomerCount,
                                  long contactEventCount, long paidOrderCount, long confirmedAppointmentCount,
                                  long serviceCompletedCount, long checkedInCustomerCount,
                                  long checkedInAppointmentCount, long redemptionBatchCount,
                                  long redeemedCustomerCount, long redeemedCount, long paidFen) {
    }

    record OperationsInstitutionFactRow(Long institutionId, String institutionName,
                                        long confirmedAppointmentCount, long serviceCompletedCount,
                                        long checkedInCustomerCount, long checkedInAppointmentCount,
                                        long redemptionBatchCount, long redeemedCustomerCount, long redeemedCount,
                                        Long pendingReconciliationFen) {
    }

    record DailyFactRow(String factType, Long parentId, Instant eventAt, long amountFen) {
    }

    record ApprovedTargetRow(Long staffUserId, long targetFen) {
    }

    record PerformanceFactRow(Long userId, String displayName, long paidOrderCount, long refundCount,
                              long paidFen, long platformPaidFen, long institutionPaidFen,
                              long refundFen, long platformRefundFen, long institutionRefundFen,
                              Instant sourceWatermark) {
    }

    record CampaignFactRow(Long campaignId, Long campaignBatchId, String campaignCode, String campaignBatchCode,
                           String campaignName, String campaignBatchName, long paidOrderCount, long refundCount,
                           long confirmedAppointmentCount, long checkedInCustomerCount,
                           long checkedInAppointmentCount, long redemptionBatchCount, long redeemedCount,
                           long paidFen, long platformPaidFen, long institutionPaidFen,
                           long refundFen, long platformRefundFen, long institutionRefundFen,
                           Instant sourceWatermark) {
    }

    record SkuQualityFactRow(Long skuId, String skuCode, Long packageVersionId, String packageName,
                             String specName, long salePriceFen, String skuStatus, int activeInstitutionCount,
                             long salesQuantity, long paidOrderCount, long paidLineCount, Instant lastSoldAt) {
    }
}
