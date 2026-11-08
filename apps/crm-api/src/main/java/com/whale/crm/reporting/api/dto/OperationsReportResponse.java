package com.whale.crm.reporting.api.dto;

import com.whale.crm.reporting.domain.ReportFact;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record OperationsReportResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                       String timeZone, String sourceSystem, String receiverType,
                                       String metricVersion, Instant sourceWatermark, OperationsMetrics metrics,
                                       Map<String, PendingRatio> conversionRates, List<StaffRow> staffRows,
                                       List<InstitutionRow> institutionRows, List<ReportFact> drillDown,
                                       String drillDownType, int drillDownLimit) {
    public OperationsReportResponse {
        conversionRates = conversionRates == null ? null : Map.copyOf(conversionRates);
        staffRows = staffRows == null ? null : List.copyOf(staffRows);
        institutionRows = institutionRows == null ? null : List.copyOf(institutionRows);
        drillDown = drillDown == null ? null : List.copyOf(drillDown);
    }

    public record OperationsMetrics(long contactCustomerCount, long contactEventCount, long paidOrderCount,
                                    long confirmedAppointmentCount, long checkedInCustomerCount,
                                    long checkedInAppointmentCount, long serviceCompletedCount,
                                    long redemptionBatchCount, long redeemedCustomerCount, long redeemedCount,
                                    long refundCount, long paidFen, long platformPaidFen, long institutionPaidFen,
                                    long refundFen, long platformRefundFen, long institutionRefundFen,
                                    long netPaidFen, long platformNetPaidFen, long institutionNetPaidFen) {
    }

    public record StaffRow(Long staffUserId, String staffName, long contactCustomerCount, long contactEventCount,
                           long paidOrderCount, long confirmedAppointmentCount, long serviceCompletedCount,
                           long checkedInCustomerCount, long checkedInAppointmentCount, long redemptionBatchCount,
                           long redeemedCustomerCount, long redeemedCount, long paidFen,
                           Map<String, PendingRatio> conversionRates) {
        public StaffRow {
            conversionRates = conversionRates == null ? null : Map.copyOf(conversionRates);
        }
    }

    public record InstitutionRow(Long institutionId, String institutionName, long confirmedAppointmentCount,
                                 long serviceCompletedCount, long checkedInCustomerCount,
                                 long checkedInAppointmentCount, long redemptionBatchCount,
                                 long redeemedCustomerCount, long redeemedCount, Long pendingReconciliationFen,
                                 String pendingReconciliationStatus, Map<String, PendingRatio> conversionRates) {
        public InstitutionRow {
            conversionRates = conversionRates == null ? null : Map.copyOf(conversionRates);
        }
    }
}
