package com.whale.crm.ai.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.ai.domain.AiInsight;
import com.whale.crm.ai.domain.AiJob;
import java.math.BigDecimal;
import java.time.Instant;

public record AiJobResponse(Long id, Long customerId, String conversationId, Long requestedBy,
                            String status, String provider, String modelName, String errorMasked,
                            Long taskId, Long version, Instant createdAt, InsightResponse insight) {
    public static AiJobResponse of(AiJob job, AiInsight value, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        try {
            InsightResponse insight = value == null ? null : new InsightResponse(value.insightType(),
                    mapper.readTree(value.projectCodesJson()), value.mentionedInstitution(), value.mentionedTime(),
                    value.absoluteTime(), mapper.readTree(value.evidenceMessageIdsJson()), value.confidence(),
                    mapper.readTree(value.proposedActionsJson()), value.factStatus(), value.allowFinancialWrite(),
                    value.reasoningSummary(), value.replyDraft(), mapper.readTree(value.followupDraftJson()),
                    mapper.readTree(value.riskFlagsJson()));
            return new AiJobResponse(job.id(), job.customerId(), job.conversationId(), job.requestedBy(), job.status(),
                    job.provider(), job.modelName(), job.errorMasked(), job.taskId(), job.version(), job.createdAt(), insight);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("AI 洞察存储结构损坏", ex);
        }
    }

    public record InsightResponse(String insightType, JsonNode projectCodes, String mentionedInstitution,
                                  String mentionedTime, Instant absoluteTime, JsonNode evidenceMessageIds,
                                  BigDecimal confidence, JsonNode proposedActions, String factStatus,
                                  Boolean allowFinancialWrite, String reasoningSummary, String replyDraft,
                                  JsonNode followupDraft, JsonNode riskFlags) { }
}
