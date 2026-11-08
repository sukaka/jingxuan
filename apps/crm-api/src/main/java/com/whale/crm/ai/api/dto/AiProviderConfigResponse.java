package com.whale.crm.ai.api.dto;

import java.time.Instant;

public record AiProviderConfigResponse(String provider, String baseUrl, boolean apiKeyConfigured, String model,
                                       int timeoutSeconds, boolean enabled, String connectionStatus,
                                       String lastErrorMasked, Instant lastConnectedAt, long version) {
}
