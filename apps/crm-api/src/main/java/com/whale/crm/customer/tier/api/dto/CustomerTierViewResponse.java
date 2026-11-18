package com.whale.crm.customer.tier.api.dto;

public record CustomerTierViewResponse(long customerId, String tierCode, boolean locked, String lockReason,
                                       long customerVersion, Long activeRuleId,
                                       CustomerTierEvaluationResponse latestSnapshot,
                                       CustomerTierReviewResponse pendingReview) {
}
