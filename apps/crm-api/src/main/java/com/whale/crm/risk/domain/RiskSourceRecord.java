package com.whale.crm.risk.domain;

public record RiskSourceRecord(
        long tenantId,
        String riskType,
        String severity,
        String sourceType,
        String sourceRef,
        String evidence) {
    public RiskSourceRecord {
        if (tenantId <= 0 || isBlank(riskType) || isBlank(severity) || isBlank(sourceType) || isBlank(sourceRef)) {
            throw new IllegalArgumentException("风险来源必须包含租户、类型、级别和稳定来源引用");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
