package com.whale.crm.reporting.domain;

import java.time.LocalDate;
import java.util.Locale;

public record ReportCriteria(LocalDate from, LocalDate to, String timeZone, String sourceSystem,
                             String receiverType, String metricVersion, Long customerId, Long campaignId,
                             Long campaignBatchId, Long channelId, Long staffUserId, Long skuId,
                             Long institutionId) {
    public ReportCriteria {
        timeZone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.trim();
        sourceSystem = normalize(sourceSystem, "ALL");
        receiverType = normalize(receiverType, "ALL");
        metricVersion = normalize(metricVersion, "P2_FACT_V1");
    }

    private static String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim().toUpperCase(Locale.ROOT);
    }
}
