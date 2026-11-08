package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanSyncCursor(Long id, String shopId, String cursorValue, Instant windowStart, Instant windowEnd,
                               Instant lastSyncAt, Instant lastExternalUpdatedAt, Instant createdAt, Long version) {
    public YouzanSyncCursor(Long id, String shopId, String cursorValue, Instant lastSyncAt,
                            Instant lastExternalUpdatedAt, Instant createdAt, Long version) {
        this(id, shopId, cursorValue, null, null, lastSyncAt, lastExternalUpdatedAt, createdAt, version);
    }
}
