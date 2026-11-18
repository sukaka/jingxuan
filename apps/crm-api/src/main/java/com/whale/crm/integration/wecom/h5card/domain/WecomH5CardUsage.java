package com.whale.crm.integration.wecom.h5card.domain;

import java.time.Instant;

public record WecomH5CardUsage(Long id, long tenantId, long cardId, long employeeId, long customerId,
                               String sendStatus, String clientResult, Instant sendTime,
                               String idempotencyKey, Instant createdAt) {
}
