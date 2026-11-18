package com.whale.crm.customer.tier.domain;

import java.math.BigDecimal;

public record TierLevelDefinition(String tierCode, String tierName, int sortNo, BigDecimal minScore,
                                  Long minConsumptionFen) {
    public TierLevelDefinition(String tierCode, String tierName, int sortNo, BigDecimal minScore) {
        this(tierCode, tierName, sortNo, minScore, null);
    }
}
