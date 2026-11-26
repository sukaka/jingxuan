package com.whale.crm.integration.youzan.customer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 有赞客户拉取游标、运行批次与逐条脱敏明细仓储接口。
 */
public interface YouzanCustomerSyncRepository {

    Optional<YouzanCustomerSyncCursor> findCursor(long tenantId, String shopId, String sourceMode);

    Optional<YouzanCustomerSyncCursor> findCursorForUpdate(long tenantId, String shopId, String sourceMode);

    void upsertCursor(YouzanCustomerSyncCursor cursor);

    void resetCursor(long tenantId, String shopId, String sourceMode);

    /**
     * 先清理租约已过期的僵尸 RUNNING 批次，再尝试插入新 RUNNING 批次；命中并发互斥唯一键返回 0。
     */
    long tryStartRun(long tenantId, String shopId, String sourceMode, String pullMode, Integer requestedLimit,
                     long ownerStaffId, String adapterName, long createdBy, Instant startedAt, Instant leaseUntil);

    int finishRun(long tenantId, long runId, String status, int fetchedCount, int createdCount,
                  int skippedExistingCount, int mobileConflictCount, int failedCount, boolean hasMore,
                  String cursorAfterJson, String warningsJson, String errorMasked, Instant finishedAt);

    Optional<YouzanCustomerSyncRun> findRun(long tenantId, long runId);

    List<YouzanCustomerSyncRun> listRuns(long tenantId, String shopId, String sourceMode, int offset, int size);

    long countRuns(long tenantId, String shopId, String sourceMode);

    void insertItem(YouzanCustomerSyncItem item);

    List<YouzanCustomerSyncItem> listItems(long tenantId, long runId, String outcome, int offset, int size);

    long countItems(long tenantId, long runId, String outcome);
}
