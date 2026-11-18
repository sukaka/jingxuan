package com.whale.crm.customer.tier.application;

import com.whale.crm.customer.tier.domain.CustomerTierMetrics;
import com.whale.crm.customer.tier.domain.TierLevelDefinition;
import com.whale.crm.customer.tier.domain.TierMetricCode;
import com.whale.crm.customer.tier.domain.TierMetricDefinition;
import com.whale.crm.customer.tier.domain.TierThresholdDirection;
import com.whale.crm.customer.tier.domain.TierWindow;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierEvaluationServiceTest {

    private final TierEvaluationService service = new TierEvaluationService();

    @Test
    void rollingWindowUsesExplicitZoneAndClampsLeapDay() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant asOf = Instant.parse("2024-02-29T04:30:00Z");

        TierWindow window = service.window(asOf, zone);

        assertThat(window.endExclusive()).isEqualTo(asOf);
        assertThat(window.startInclusive()).isEqualTo(Instant.parse("2023-02-28T04:30:00Z"));
        assertThat(window.zoneId()).isEqualTo(zone);
    }

    @Test
    void actualConsumptionSubtractsOnlySuccessfulRefundAndSelectsBForEightThousandYuan() {
        CustomerTierMetrics values = new CustomerTierMetrics(1_200_000L, 1_000_000L, 200_000L, 1L,
                20L, 30L, true);

        TierEvaluation evaluation = service.evaluate(levels(1_000_000L, 800_001L, 1_000L, 1L, 0L),
                List.of(), values);

        assertThat(values.actualConsumptionFen()).isEqualTo(800_000L);
        assertThat(evaluation.complete()).isTrue();
        assertThat(evaluation.suggestedTierCode()).isEqualTo("B");
        assertThat(evaluation.score()).isNull();
    }

    @Test
    void exactConsumptionThresholdSelectsThatLevel() {
        CustomerTierMetrics values = new CustomerTierMetrics(0L, 800_100L, 0L, 1L, 0L, 0L, true);

        TierEvaluation evaluation = service.evaluate(levels(1_000_000L, 800_001L, 1_000L, 1L, 0L),
                List.of(), values);

        assertThat(values.actualConsumptionFen()).isEqualTo(800_100L);
        assertThat(evaluation.suggestedTierCode()).isEqualTo("A");
    }

    @Test
    void refundsAbovePaidAmountClampToZeroAndIncompleteArchiveDoesNotAffectTier() {
        CustomerTierMetrics values = new CustomerTierMetrics(0L, 1_000L, 2_000L, 0L,
                0L, 0L, false);

        TierEvaluation evaluation = service.evaluate(levels(1_000_000L, 800_001L, 1_000L, 1L, 0L),
                List.of(), values);

        assertThat(values.actualConsumptionFen()).isZero();
        assertThat(values.communicationCount()).isNull();
        assertThat(evaluation.complete()).isTrue();
        assertThat(evaluation.suggestedTierCode()).isEqualTo("D");
        assertThat(evaluation.score()).isNull();
    }

    @Test
    void legacyWeightedRuleWithoutConsumptionThresholdsDoesNotSuggestTier() {
        List<TierLevelDefinition> legacyLevels = List.of(
                new TierLevelDefinition("S", "核心", 1, new BigDecimal("90")),
                new TierLevelDefinition("A", "重要", 2, new BigDecimal("70")),
                new TierLevelDefinition("B", "成长", 3, new BigDecimal("50")),
                new TierLevelDefinition("C", "普通", 4, new BigDecimal("20")),
                new TierLevelDefinition("D", "培育", 5, BigDecimal.ZERO));
        List<TierMetricDefinition> legacyMetrics = List.of(new TierMetricDefinition(
                TierMetricCode.PAID_AMOUNT_FEN, TierThresholdDirection.GTE, new BigDecimal("100"),
                Map.of("S", 10_000L, "A", 8_001L, "B", 1_000L, "C", 1L, "D", 0L)));

        TierEvaluation evaluation = service.evaluate(legacyLevels, legacyMetrics,
                new CustomerTierMetrics(0L, 1_000_000L, 0L, 1L, 0L, 0L, true));

        assertThat(evaluation.complete()).isFalse();
        assertThat(evaluation.score()).isNull();
        assertThat(evaluation.suggestedTierCode()).isNull();
    }

    @Test
    void rejectsMissingOrInvalidConsumptionThresholdsAndWeightedMetrics() {
        assertThatThrownBy(() -> service.validate(levels(null, 8_001L, 1_000L, 1L, 0L), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.validate(levels(1_000L, 8_001L, 1_000L, 1L, 0L), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能低于");
        assertThatThrownBy(() -> service.validate(levels(10_000L, 8_001L, 1_000L, 1L, 1L), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须为 0");
        assertThatThrownBy(() -> service.validate(levels(1_000_000L, 800_001L, 1_000L, 1L, 0L),
                List.of(new TierMetricDefinition(TierMetricCode.PAID_AMOUNT_FEN, TierThresholdDirection.GTE,
                        new BigDecimal("100"), Map.of("S", 1L)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不需要指标、权重或得分");
    }

    private List<TierLevelDefinition> levels(Long s, Long a, Long b, Long c, Long d) {
        return List.of(
                new TierLevelDefinition("S", "核心", 1, null, s),
                new TierLevelDefinition("A", "重要", 2, null, a),
                new TierLevelDefinition("B", "成长", 3, null, b),
                new TierLevelDefinition("C", "普通", 4, null, c),
                new TierLevelDefinition("D", "培育", 5, null, d));
    }
}
