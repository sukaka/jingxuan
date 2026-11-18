package com.whale.crm.customer.tier.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerTierEvaluationResponse(Long snapshotId, long customerId, long ruleId, Instant asOf,
                                             Instant windowStartInclusive, Instant windowEndExclusive,
                                             String timezone, long paymentAmountFen, long paidAmountFen,
                                             long refundAmountFen, long orderCount, long manualFollowupCount,
                                             long archivedMessageCount, Long communicationCount,
                                             boolean communicationComplete, Long actualConsumptionFen,
                                             BigDecimal score, String suggestedTierCode, String sourceTrigger) {
}
