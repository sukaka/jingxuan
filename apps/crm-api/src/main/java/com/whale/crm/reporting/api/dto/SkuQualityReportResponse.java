package com.whale.crm.reporting.api.dto;

import com.whale.crm.reporting.domain.ReportFact;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SkuQualityReportResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                       String timeZone, String sourceSystem, String receiverType,
                                       String metricVersion, Instant sourceWatermark, int lowFrequencyThreshold,
                                       long periodDays, String lowFrequencyAction, List<QualityRule> qualityRules,
                                       List<SkuQualityRow> items, long total, int page, int size,
                                       List<ReportFact> drillDown, String drillDownType, int drillDownLimit) {
    public SkuQualityReportResponse {
        qualityRules = qualityRules == null ? null : List.copyOf(qualityRules);
        items = items == null ? null : List.copyOf(items);
        drillDown = drillDown == null ? null : List.copyOf(drillDown);
    }

    public record QualityRule(String code, String description) {
    }

    public record SkuQualityRow(Long skuId, String skuCode, Long packageVersionId, String packageName,
                                String specName, String standardName, long salePriceFen, String skuStatus,
                                int activeInstitutionCount, long salesQuantity, long paidOrderCount,
                                long paidLineCount, Instant lastSoldAt, boolean lowFrequency,
                                String qualityStatus, List<String> issueCodes, String automaticAction) {
        public SkuQualityRow {
            issueCodes = issueCodes == null ? null : List.copyOf(issueCodes);
        }
    }
}
