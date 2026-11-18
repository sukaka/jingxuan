package com.whale.crm.customer.tier.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerTierMetricSnapshot(Long id, long tenantId, long customerId, long ruleId,
                                         String recalculationKey, String sourceTrigger, Instant asOf,
                                         Instant windowStartInclusive, Instant windowEndExclusive, String zoneId,
                                         long paymentAmountFen, long paidAmountFen, long refundAmountFen,
                                         long orderCount, long manualFollowupCount, long archivedMessageCount,
                                         Long communicationCount, boolean communicationComplete,
                                         Long actualConsumptionFen, BigDecimal score,
                                         String suggestedTierCode, Instant createdAt) {
    public CustomerTierMetrics metrics() {
        return new CustomerTierMetrics(paymentAmountFen, paidAmountFen, refundAmountFen, orderCount,
                manualFollowupCount, archivedMessageCount, communicationComplete);
    }
}
