package com.whale.crm.ai.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record AiInsight(Long id, Long tenantId, Long jobId, String insightType, String projectCodesJson,
                        String mentionedInstitution, String mentionedTime, Instant absoluteTime,
                        String evidenceMessageIdsJson, BigDecimal confidence, String proposedActionsJson,
                        String factStatus, Boolean allowFinancialWrite, String reasoningSummary,
                        String replyDraft, String followupDraftJson, String riskFlagsJson, Instant createdAt) {
}
