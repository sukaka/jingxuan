package com.whale.crm.integration.youzan.customer.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 有赞客户拉取运行批次响应。
 */
public record YouzanCustomerSyncRunResponse(
        Long id,
        String shopId,
        String sourceMode,
        String pullMode,
        Integer requestedLimit,
        long ownerStaffId,
        String ownerName,
        String adapterName,
        String status,
        int fetchedCount,
        int createdCount,
        int skippedExistingCount,
        int mobileConflictCount,
        int failedCount,
        boolean hasMore,
        List<String> warnings,
        String errorMasked,
        Instant startedAt,
        Instant finishedAt,
        List<YouzanCustomerSyncItemResponse> items
) {
    public YouzanCustomerSyncRunResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        items = items == null ? null : List.copyOf(items);
    }
}
