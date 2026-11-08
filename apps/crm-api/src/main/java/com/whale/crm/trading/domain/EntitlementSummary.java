package com.whale.crm.trading.domain;

public record EntitlementSummary(long availableCount, long customerCount) {
    public static EntitlementSummary empty() {
        return new EntitlementSummary(0L, 0L);
    }
}
