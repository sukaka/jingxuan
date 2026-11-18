package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiChatDelegationRun(Long id, long tenantId, Long sourceMessageId, Long customerId,
                                  Long staffUserId, Long teamId, String corpId, String externalUserid,
                                  String conversationId, String status, String reasonMasked, String replyCipher,
                                  String intent, String riskFlagsJson, String aiProvider, String aiModel,
                                  String sendProvider, String providerMessageId, Instant sendAttemptedAt,
                                  int generationAttempts, int sendAttempts, Instant nextAttemptAt,
                                  String leaseOwner, Instant leaseExpiresAt, Instant createdAt,
                                  Instant updatedAt, long version) {
}
