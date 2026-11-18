package com.whale.crm.quickreply.domain;

import java.time.Instant;

public record QuickReplyUsage(Long id, long tenantId, long templateId, long employeeId, long customerId,
                              String externalUserid, String scene, String originalContent, String finalContent,
                              boolean edited, String sendStatus, String clientResult, Long aiSuggestionId,
                              Instant sendTime, String idempotencyKey, Instant createdAt) {
}
