package com.whale.crm.risk.domain;

import java.time.Instant;

public record RiskCase(
        Long id,
        long tenantId,
        String riskCaseNo,
        String riskType,
        String severity,
        String sourceType,
        String sourceRef,
        String status,
        Long ownerUserId,
        String evidence,
        String resolution,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
