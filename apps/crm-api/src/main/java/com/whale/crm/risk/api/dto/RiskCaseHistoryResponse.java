package com.whale.crm.risk.api.dto;

import com.whale.crm.risk.domain.RiskCaseHistory;

import java.time.Instant;

public record RiskCaseHistoryResponse(
        Long id,
        long riskCaseId,
        String action,
        String fromStatus,
        String toStatus,
        Long actorUserId,
        String note,
        Instant createdAt) {
    public static RiskCaseHistoryResponse of(RiskCaseHistory history) {
        return new RiskCaseHistoryResponse(history.id(), history.riskCaseId(), history.action(), history.fromStatus(),
                history.toStatus(), history.actorUserId(), history.note(), history.createdAt());
    }
}
