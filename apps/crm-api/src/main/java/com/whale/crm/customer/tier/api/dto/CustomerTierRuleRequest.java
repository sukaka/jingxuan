package com.whale.crm.customer.tier.api.dto;

import com.whale.crm.customer.tier.domain.TierMetricCode;
import com.whale.crm.customer.tier.domain.TierThresholdDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public record CustomerTierRuleRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,63}") String ruleCode,
        @NotBlank @Size(max = 128) String ruleName,
        @NotBlank @Size(max = 64) String timezone,
        @NotEmpty @Size(min = 5, max = 5) List<@Valid Level> levels,
        List<@Valid Metric> metrics) {
    public CustomerTierRuleRequest {
        levels = levels == null ? null : List.copyOf(levels);
        metrics = metrics == null ? null : List.copyOf(metrics);
    }

    public record Level(@NotBlank @Pattern(regexp = "S|A|B|C|D") String tierCode,
                        @NotBlank @Size(max = 64) String tierName,
                        @Min(1) @Max(5) int sortNo,
                        @DecimalMin("0") @DecimalMax("100") BigDecimal minScore,
                        @Min(0) Long minConsumptionFen) {
    }

    public record Metric(@NotNull TierMetricCode metricCode, @NotNull TierThresholdDirection direction,
                         @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal weight,
                         @NotEmpty @Size(min = 5, max = 5) List<@Valid Threshold> thresholds) {
        public Metric {
            thresholds = thresholds == null ? null : List.copyOf(thresholds);
        }
    }

    public record Threshold(@NotBlank @Pattern(regexp = "S|A|B|C|D") String tierCode,
                            @NotNull @Min(0) Long value) {
    }
}
