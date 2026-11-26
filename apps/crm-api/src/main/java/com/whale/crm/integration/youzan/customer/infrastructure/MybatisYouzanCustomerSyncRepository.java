package com.whale.crm.integration.youzan.customer.infrastructure;

import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncCursor;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncItem;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncRepository;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncRun;
import com.whale.crm.integration.youzan.customer.infrastructure.mapper.YouzanCustomerSyncMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisYouzanCustomerSyncRepository implements YouzanCustomerSyncRepository {

    private final YouzanCustomerSyncMapper mapper;

    public MybatisYouzanCustomerSyncRepository(YouzanCustomerSyncMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<YouzanCustomerSyncCursor> findCursor(long tenantId, String shopId, String sourceMode) {
        return Optional.ofNullable(mapper.selectCursor(tenantId, shopId, sourceMode));
    }

    @Override
    public Optional<YouzanCustomerSyncCursor> findCursorForUpdate(long tenantId, String shopId, String sourceMode) {
        return Optional.ofNullable(mapper.selectCursorForUpdate(tenantId, shopId, sourceMode));
    }

    @Override
    public void upsertCursor(YouzanCustomerSyncCursor cursor) {
        mapper.upsertCursor(cursor);
    }

    @Override
    public void resetCursor(long tenantId, String shopId, String sourceMode) {
        mapper.deleteCursor(tenantId, shopId, sourceMode);
    }

    @Override
    public long tryStartRun(long tenantId, String shopId, String sourceMode, String pullMode, Integer requestedLimit,
                            long ownerStaffId, String adapterName, long createdBy,
                            Instant startedAt, Instant leaseUntil) {
        mapper.expireStaleRuns(tenantId, shopId, sourceMode, startedAt);
        YouzanCustomerSyncMapper.RunInsertRow row = new YouzanCustomerSyncMapper.RunInsertRow();
        row.setTenantId(tenantId);
        row.setShopId(shopId);
        row.setSourceMode(sourceMode);
        row.setPullMode(pullMode);
        row.setRequestedLimit(requestedLimit);
        row.setOwnerStaffId(ownerStaffId);
        row.setAdapterName(adapterName);
        row.setStatus("RUNNING");
        row.setLeaseUntil(leaseUntil);
        row.setCreatedBy(createdBy);
        row.setStartedAt(startedAt);
        try {
            mapper.insertRun(row);
            return row.getId() == null ? 0L : row.getId();
        } catch (DuplicateKeyException ex) {
            return 0L;
        }
    }

    @Override
    public int finishRun(long tenantId, long runId, String status, int fetchedCount, int createdCount,
                         int skippedExistingCount, int mobileConflictCount, int failedCount, boolean hasMore,
                         String cursorAfterJson, String warningsJson, String errorMasked, Instant finishedAt) {
        return mapper.finishRun(tenantId, runId, status, fetchedCount, createdCount, skippedExistingCount,
                mobileConflictCount, failedCount, hasMore, cursorAfterJson, warningsJson, errorMasked, finishedAt);
    }

    @Override
    public Optional<YouzanCustomerSyncRun> findRun(long tenantId, long runId) {
        return Optional.ofNullable(mapper.selectRun(tenantId, runId));
    }

    @Override
    public List<YouzanCustomerSyncRun> listRuns(long tenantId, String shopId, String sourceMode,
                                                int offset, int size) {
        return mapper.selectRuns(tenantId, shopId, sourceMode, offset, size);
    }

    @Override
    public long countRuns(long tenantId, String shopId, String sourceMode) {
        return mapper.countRuns(tenantId, shopId, sourceMode);
    }

    @Override
    public void insertItem(YouzanCustomerSyncItem item) {
        mapper.insertItem(item);
    }

    @Override
    public List<YouzanCustomerSyncItem> listItems(long tenantId, long runId, String outcome, int offset, int size) {
        return mapper.selectItems(tenantId, runId, outcome, offset, size);
    }

    @Override
    public long countItems(long tenantId, long runId, String outcome) {
        return mapper.countItems(tenantId, runId, outcome);
    }
}
