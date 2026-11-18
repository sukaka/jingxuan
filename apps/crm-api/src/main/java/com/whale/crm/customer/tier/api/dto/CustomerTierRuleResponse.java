package com.whale.crm.customer.tier.api.dto;

import com.whale.crm.customer.tier.domain.TierMetricCode;
import com.whale.crm.customer.tier.domain.TierThresholdDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerTierRuleResponse(long id, String ruleCode, int versionNo, String ruleName, String timezone,
                                       String status, List<Level> levels, List<Metric> metrics,
                                       Instant activatedAt, Instant retiredAt, Instant createdAt, long version) {
    public CustomerTierRuleResponse {
        levels = levels == null ? null : List.copyOf(levels);
        metrics = metrics == null ? null : List.copyOf(metrics);
    }

    public record Level(String tierCode, String tierName, int sortNo, BigDecimal minScore,
                        Long minConsumptionFen) {
    }

    public record Metric(TierMetricCode metricCode, TierThresholdDirection direction, BigDecimal weight,
                         List<Threshold> thresholds) {
        public Metric {
            thresholds = thresholds == null ? null : List.copyOf(thresholds);
        }
    }

    public record Threshold(String tierCode, long value) {
    }
}
