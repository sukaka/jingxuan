package com.whale.crm.customer.tier.domain;

public record CustomerTierMetrics(long paymentAmountFen, long paidAmountFen, long refundAmountFen,
                                  long orderCount, long manualFollowupCount, long archivedMessageCount,
                                  boolean communicationComplete) {
    public Long communicationCount() {
        return communicationComplete ? manualFollowupCount + archivedMessageCount : null;
    }

    public long actualConsumptionFen() {
        return paidAmountFen <= refundAmountFen ? 0 : paidAmountFen - refundAmountFen;
    }
}
