package com.whale.crm.ai.domain;

import java.time.Instant;

public record DifyKnowledgeConfig(Long id, Long tenantId, String provider, String baseUrl, String appApiKeyCipher,
                                  String datasetId, String datasetApiKeyCipher, Boolean syncEnabled,
                                  Integer timeoutSeconds, Boolean enabled, String connectionStatus,
                                  String lastErrorMasked, Instant lastConnectedAt, Instant createdAt,
                                  Instant updatedAt, Long version) {
}
