package com.whale.crm.ai.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AiReplySuggestionResponse(long id, long templateId, long customerId, String scene, String reply,
                                        String reason, List<String> usedFactCodes, String provider, String model,
                                        String promptVersion, BigDecimal confidence, String status, boolean accepted,
                                        boolean edited, String finalContent, Instant createdAt, Instant decidedAt,
                                        long version) {
    public AiReplySuggestionResponse {
        usedFactCodes = usedFactCodes == null ? null : List.copyOf(usedFactCodes);
    }
}
