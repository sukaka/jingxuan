package com.whale.crm.integration.youzan.customer.domain;

import java.time.Instant;

/**
 * 有赞客户拉取断点游标记录（按 tenant_id + shop_id + source_mode 唯一）。
 */
public record YouzanCustomerSyncCursor(
        Long id,
        long tenantId,
        String shopId,
        String sourceMode,
        String cursorJson,
        boolean hasMore,
        Instant lastAnchorAt,
        Instant lastSyncedAt,
        long totalImportedCount,
        Instant createdAt,
        Instant updatedAt,
        long version
) {
}
