package com.whale.crm.customer.tier.domain;

import java.math.BigDecimal;
import java.util.Map;

public record TierMetricDefinition(TierMetricCode metricCode, TierThresholdDirection direction,
                                   BigDecimal weight, Map<String, Long> thresholds) {
    public TierMetricDefinition {
        thresholds = thresholds == null ? null : Map.copyOf(thresholds);
    }
}
