package com.whale.crm.risk.api.dto;

import java.util.List;

public record RiskCaseDetailResponse(
        RiskCaseResponse riskCase,
        List<RiskCaseHistoryResponse> history) {
    public RiskCaseDetailResponse {
        history = history == null ? null : List.copyOf(history);
    }
}
