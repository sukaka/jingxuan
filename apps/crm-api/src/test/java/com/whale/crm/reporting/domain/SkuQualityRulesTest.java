package com.whale.crm.reporting.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkuQualityRulesTest {

    @Test
    void usesInclusiveLowFrequencyThresholdWithoutChangingSaleStatus() {
        SkuQualityAssessment assessment = SkuQualityRules.evaluate(
                new SkuQualityFacts("SKU-1", "水光卡", "3次", 2, 3), 3);

        assertThat(assessment.lowFrequency()).isTrue();
        assertThat(assessment.issueCodes()).containsExactly("LOW_FREQUENCY");
        assertThat(assessment.qualityStatus()).isEqualTo("ATTENTION");
        assertThat(assessment.automaticAction()).isEqualTo("NONE");
    }

    @Test
    void reportsOnlyDeterministicConfigurationDefects() {
        SkuQualityAssessment assessment = SkuQualityRules.evaluate(
                new SkuQualityFacts(" ", " ", "", 0, 0), 0);

        assertThat(assessment.issueCodes()).containsExactly(
                "MISSING_SKU_CODE", "MISSING_STANDARD_NAME", "MISSING_ACTIVE_INSTITUTION", "LOW_FREQUENCY");
        assertThat(assessment.automaticAction()).isEqualTo("NONE");
    }

    @Test
    void paidSalesAboveThresholdAndCompleteConfigurationAreNormal() {
        SkuQualityAssessment assessment = SkuQualityRules.evaluate(
                new SkuQualityFacts("SKU-2", "水光卡", "6次", 1, 4), 3);

        assertThat(assessment.issueCodes()).isEmpty();
        assertThat(assessment.qualityStatus()).isEqualTo("NORMAL");
        assertThat(assessment.lowFrequency()).isFalse();
    }
}
