package com.whale.crm.risk.domain;

public record RiskCaseQuery(
        long tenantId,
        String status,
        String severity,
        String riskType,
        String sourceType,
        String sourceRef,
        Long ownerUserId,
        String keyword,
        int offset,
        int size) {
}
