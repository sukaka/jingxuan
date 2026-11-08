package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomSyncPolicy(Long id, Long tenantId, String corpId, Boolean enabled,
                              Boolean contactSyncEnabled, Boolean archiveSyncEnabled, Integer intervalSeconds,
                              Instant nextRunAt, Instant lastScheduledAt, String lastStatus,
                              String lastError, Long version) {
}
