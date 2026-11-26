package com.whale.crm.integration.youzan.customer.api.dto;

import java.time.Instant;

/**
 * 单个有赞客户拉取数据源的持久化断点游标摘要。
 */
public record YouzanCustomerCursorSummary(
        String sourceMode,
        boolean hasCursor,
        boolean hasMore,
        Integer pageNo,
        Integer itemOffset,
        Instant windowStart,
        Instant windowEnd,
        Instant lastAnchorAt,
        Instant lastSyncedAt,
        long totalImportedCount
) {
}
