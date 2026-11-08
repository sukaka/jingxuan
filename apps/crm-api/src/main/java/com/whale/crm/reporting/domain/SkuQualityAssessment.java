package com.whale.crm.reporting.domain;

import java.util.List;

public record SkuQualityAssessment(List<String> issueCodes, String qualityStatus,
                                   boolean lowFrequency, String automaticAction) {
    public SkuQualityAssessment {
        issueCodes = List.copyOf(issueCodes);
    }
}
