package com.whale.crm.integration.youzan.customer.domain;

import java.time.Instant;

/**
 * 有赞客户拉取建档运行批次（带 running_shop_key 并发互斥锁）。
 */
public record YouzanCustomerSyncRun(
        Long id,
        long tenantId,
        String shopId,
        String sourceMode,
        String pullMode,
        Integer requestedLimit,
        long ownerStaffId,
        String adapterName,
        String status,
        int fetchedCount,
        int createdCount,
        int skippedExistingCount,
        int mobileConflictCount,
        int failedCount,
        boolean hasMore,
        String cursorAfterJson,
        String warningsJson,
        String errorMasked,
        Instant leaseUntil,
        long createdBy,
        Instant startedAt,
        Instant finishedAt
) {
}
