package com.whale.crm.settlement.api.dto;

import com.whale.crm.settlement.domain.ReconciliationIssue;

import java.time.Instant;
import java.util.List;

public record ReconciliationResponse(Instant generatedAt, int issueCount, List<ReconciliationIssue> issues) {
    public ReconciliationResponse {
        issues = issues == null ? null : List.copyOf(issues);
    }
}
