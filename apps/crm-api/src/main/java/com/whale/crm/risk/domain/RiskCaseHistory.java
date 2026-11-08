package com.whale.crm.risk.domain;

import java.time.Instant;

public record RiskCaseHistory(
        Long id,
        long tenantId,
        long riskCaseId,
        String action,
        String fromStatus,
        String toStatus,
        Long actorUserId,
        String note,
        Instant createdAt) {
}
