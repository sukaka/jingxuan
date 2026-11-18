package com.whale.crm.integration.wecom.h5card.api.dto;

import java.time.Instant;

public record WecomH5CardUsageResponse(long id, long cardId, long customerId, String sendStatus,
                                       String clientResult, Instant sendTime, Instant createdAt) {
}
