package com.whale.crm.ai.api.dto;

import com.whale.crm.ai.domain.AiChatDelegationRun;

import java.time.Instant;

public record AiChatDelegationRunResponse(long id, long sourceMessageId, Long customerId, long staffUserId,
                                          Long teamId, String status, String reasonMasked, String intent,
                                          String aiProvider, String aiModel, String sendProvider,
                                          String providerMessageId, Instant sendAttemptedAt,
                                          int generationAttempts, int sendAttempts, Instant nextAttemptAt,
                                          Instant createdAt, Instant updatedAt, long version) {
    public static AiChatDelegationRunResponse of(AiChatDelegationRun run) {
        return new AiChatDelegationRunResponse(run.id(), run.sourceMessageId(), run.customerId(), run.staffUserId(),
                run.teamId(), run.status(), run.reasonMasked(), run.intent(), run.aiProvider(), run.aiModel(),
                run.sendProvider(), run.providerMessageId(), run.sendAttemptedAt(), run.generationAttempts(),
                run.sendAttempts(), run.nextAttemptAt(), run.createdAt(), run.updatedAt(), run.version());
    }
}
