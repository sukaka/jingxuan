package com.whale.crm.reporting.domain;

public record SkuQualityFacts(String skuCode, String packageName, String specName,
                              int activeInstitutionCount, long salesQuantity) {
}
