package com.whale.crm.customer.tier.application;

import java.math.BigDecimal;

public record TierEvaluation(BigDecimal score, String suggestedTierCode, boolean complete) {
}
