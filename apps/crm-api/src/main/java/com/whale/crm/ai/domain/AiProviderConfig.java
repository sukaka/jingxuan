package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiProviderConfig(Long id, Long tenantId, String provider, String baseUrl, String apiKeyCipher,
                               String model, Integer timeoutSeconds, Boolean enabled, String connectionStatus,
                               String lastErrorMasked, Instant lastConnectedAt, Instant createdAt,
                               Instant updatedAt, Long version) {
}
