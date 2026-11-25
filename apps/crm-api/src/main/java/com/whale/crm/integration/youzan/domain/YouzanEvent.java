package com.whale.crm.integration.youzan.domain;

import org.apache.ibatis.annotations.AutomapConstructor;

import java.time.Instant;

public record YouzanEvent(Long id, String shopId, String eventId, String eventType, String externalOrderNo,
                          String externalCustomerId, Long campaignId,
                          String payloadMaskedJson, String payloadCipher, Boolean signatureValid,
                          String sourceSystem, String processStatus, Integer processAttempts, String lastError,
                          Instant nextRetryAt, Instant receivedAt, Instant processedAt, Long orderId,
                          String providerPayloadMaskedJson, String providerPayloadCipher, Long version) {
    @AutomapConstructor
    public YouzanEvent {
    }

    public YouzanEvent(Long id, String shopId, String eventId, String eventType, String externalOrderNo,
                       String payloadMaskedJson, String payloadCipher, Boolean signatureValid,
                       String sourceSystem, String processStatus, Integer processAttempts, String lastError,
                       Instant nextRetryAt, Instant receivedAt, Instant processedAt, Long orderId,
                       String providerPayloadMaskedJson, String providerPayloadCipher, Long version) {
        this(id, shopId, eventId, eventType, externalOrderNo, null, null, payloadMaskedJson, payloadCipher,
                signatureValid, sourceSystem, processStatus, processAttempts, lastError, nextRetryAt, receivedAt,
                processedAt, orderId, providerPayloadMaskedJson, providerPayloadCipher, version);
    }

    public YouzanEvent(Long id, String shopId, String eventId, String eventType, String externalOrderNo,
                       String payloadMaskedJson, String payloadCipher, Boolean signatureValid,
                       String sourceSystem, String processStatus, Integer processAttempts, String lastError,
                       Instant nextRetryAt, Instant receivedAt, Instant processedAt, Long orderId, Long version) {
        this(id, shopId, eventId, eventType, externalOrderNo, null, null, payloadMaskedJson, payloadCipher,
                signatureValid, sourceSystem, processStatus, processAttempts, lastError, nextRetryAt, receivedAt,
                processedAt, orderId, null, null, version);
    }
}
