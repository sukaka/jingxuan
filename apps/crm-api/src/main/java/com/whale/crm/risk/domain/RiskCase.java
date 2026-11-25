package com.whale.crm.risk.domain;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.time.Instant;

public record RiskCase(
        Long id,
        long tenantId,
        String riskCaseNo,
        String riskType,
        String severity,
        String sourceType,
        String sourceRef,
        Long customerId,
        Long orderId,
        String status,
        Long ownerUserId,
        String evidence,
        String resolution,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
    @AutomapConstructor
    public RiskCase {
    }

    public RiskCase(Long id, long tenantId, String riskCaseNo, String riskType, String severity, String sourceType,
                    String sourceRef, String status, Long ownerUserId, String evidence, String resolution,
                    Instant resolvedAt, Instant createdAt, Instant updatedAt, long version) {
        this(id, tenantId, riskCaseNo, riskType, severity, sourceType, sourceRef, null, null, status, ownerUserId,
                evidence, resolution, resolvedAt, createdAt, updatedAt, version);
    }
}
