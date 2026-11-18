package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiChatSendFence(Long id, long tenantId, long runId, long customerId, boolean blocked,
                              boolean inFlight, Instant startedAt, Instant finishedAt, long version) {
}
