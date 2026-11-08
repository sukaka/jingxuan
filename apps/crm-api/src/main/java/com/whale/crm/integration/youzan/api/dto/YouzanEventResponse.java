package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanEventResponse(Long id, String eventId, String eventType, String externalOrderNo,
                                  String processStatus, Integer processAttempts, String lastError,
                                  Long orderId, Instant receivedAt, Instant processedAt, Boolean duplicate) {
}
