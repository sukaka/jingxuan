package com.whale.crm.reporting.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record WecomAnalyticsResponse(LocalDate from, LocalDate to, Instant fromUtc, Instant toUtcExclusive,
                                     String timeZone, String metricVersion, String scopeType, Long employeeId,
                                     Instant generatedAt, QuickReplyMetrics quickReply, AiReplyMetrics aiReply,
                                     ConversionMetrics conversion, List<String> unsupportedMetrics) {
    public WecomAnalyticsResponse {
        unsupportedMetrics = List.copyOf(unsupportedMetrics);
    }

    public record QuickReplyMetrics(long usageCount, long copiedCount, long sdkSuccessCount, long sdkFailedCount,
                                    long sdkUnknownCount, long editedCount, long aiLinkedUsageCount,
                                    BigDecimal sdkSuccessRate) {
    }

    public record AiReplyMetrics(long generatedCount, long acceptedCount, long rejectedCount, long pendingCount,
                                 long editedAcceptedCount, long mockGeneratedCount, long copiedCount,
                                 long sdkSuccessCount, long sdkFailedCount, long sdkUnknownCount,
                                 BigDecimal cohortAcceptanceRate) {
    }

    public record ConversionMetrics(long confirmedAppointmentCount, long checkedInFromConfirmedCount,
                                    long paidOrderCount, long paidCustomerCount, long repeatPaidCustomerCount,
                                    BigDecimal appointmentArrivalRate, BigDecimal repeatCustomerRate) {
    }
}
