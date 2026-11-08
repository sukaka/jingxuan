package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanSyncPolicy(Long id, String shopId, Instant initialStartTime, Integer intervalSeconds,
                               Integer overlapSeconds, Integer pageSize, Boolean enabled, Instant nextRunAt,
                               Instant lastScheduledAt, Long version) {
}
