package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiChatDelegationAttempt(Long id, long tenantId, long runId, String phase, String result,
                                      String provider, String providerMessageId, String errorMasked,
                                      Instant createdAt) {
}
