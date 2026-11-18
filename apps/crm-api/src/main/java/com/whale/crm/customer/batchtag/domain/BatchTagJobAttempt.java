package com.whale.crm.customer.batchtag.domain;

import java.time.Instant;

public record BatchTagJobAttempt(Long id, long tenantId, long jobId, int attemptNo, String status,
                                 String triggerType, long actorUserId, String errorMasked, Instant createdAt) {
}
