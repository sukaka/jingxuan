package com.whale.crm.customer.tier.domain;

import java.math.BigDecimal;

public record CustomerTierLevel(Long id, long tenantId, long ruleId, String tierCode, String tierName,
                                int sortNo, BigDecimal minScore, Long minConsumptionFen) {
    public TierLevelDefinition toDefinition() {
        return new TierLevelDefinition(tierCode, tierName, sortNo, minScore, minConsumptionFen);
    }
}
