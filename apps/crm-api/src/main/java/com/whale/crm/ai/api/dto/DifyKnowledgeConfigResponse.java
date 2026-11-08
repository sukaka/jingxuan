package com.whale.crm.ai.api.dto;

import java.time.Instant;

public record DifyKnowledgeConfigResponse(String provider, String baseUrl, boolean apiKeyConfigured,
                                          String datasetId, boolean datasetApiKeyConfigured, boolean syncEnabled,
                                          int timeoutSeconds, boolean enabled, String connectionStatus,
                                          String lastErrorMasked, Instant lastConnectedAt, long version) {
}
