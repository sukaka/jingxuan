package com.whale.crm.ai.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record AiReplySuggestion(Long id, long tenantId, long templateId, long customerId, long employeeId,
                                String scene, String factsSnapshotJson, String requestHash, String idempotencyKey,
                                String replyCipher, String reasonMasked, String usedFactCodesJson, String provider,
                                String modelName, String promptVersion, BigDecimal confidence, String status,
                                boolean accepted, boolean edited, String finalContentCipher, String decisionReason,
                                Long decisionBy, Instant decisionAt, String decisionKey, String decisionHash,
                                Instant createdAt, Instant updatedAt, long version) {
}
