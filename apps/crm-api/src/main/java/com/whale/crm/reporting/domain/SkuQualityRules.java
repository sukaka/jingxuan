package com.whale.crm.reporting.domain;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.util.ArrayList;
import java.util.List;

public final class SkuQualityRules {
    public static final int MAX_LOW_FREQUENCY_THRESHOLD = 1000;

    private SkuQualityRules() {
    }

    public static SkuQualityAssessment evaluate(SkuQualityFacts facts, int lowFrequencyThreshold) {
        if (lowFrequencyThreshold < 0 || lowFrequencyThreshold > MAX_LOW_FREQUENCY_THRESHOLD) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "低频阈值必须在 0 到 1000 之间");
        }
        List<String> issues = new ArrayList<>();
        if (facts.skuCode() == null || facts.skuCode().isBlank()) {
            issues.add("MISSING_SKU_CODE");
        }
        if (facts.packageName() == null || facts.packageName().isBlank()
                || facts.specName() == null || facts.specName().isBlank()) {
            issues.add("MISSING_STANDARD_NAME");
        }
        if (facts.activeInstitutionCount() == 0) {
            issues.add("MISSING_ACTIVE_INSTITUTION");
        }
        boolean lowFrequency = facts.salesQuantity() <= lowFrequencyThreshold;
        if (lowFrequency) {
            issues.add("LOW_FREQUENCY");
        }
        return new SkuQualityAssessment(List.copyOf(issues), issues.isEmpty() ? "NORMAL" : "ATTENTION",
                lowFrequency, "NONE");
    }
}
