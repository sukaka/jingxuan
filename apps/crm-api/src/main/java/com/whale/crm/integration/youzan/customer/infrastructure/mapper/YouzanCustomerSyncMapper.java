package com.whale.crm.integration.youzan.customer.infrastructure.mapper;

import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncCursor;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncItem;
import com.whale.crm.integration.youzan.customer.domain.YouzanCustomerSyncRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface YouzanCustomerSyncMapper {

    YouzanCustomerSyncCursor selectCursor(@Param("tenantId") long tenantId,
                                          @Param("shopId") String shopId,
                                          @Param("sourceMode") String sourceMode);

    YouzanCustomerSyncCursor selectCursorForUpdate(@Param("tenantId") long tenantId,
                                                   @Param("shopId") String shopId,
                                                   @Param("sourceMode") String sourceMode);

    int upsertCursor(YouzanCustomerSyncCursor cursor);

    int deleteCursor(@Param("tenantId") long tenantId,
                     @Param("shopId") String shopId,
                     @Param("sourceMode") String sourceMode);

    int expireStaleRuns(@Param("tenantId") long tenantId,
                        @Param("shopId") String shopId,
                        @Param("sourceMode") String sourceMode,
                        @Param("now") Instant now);

    int insertRun(RunInsertRow row);

    int finishRun(@Param("tenantId") long tenantId,
                  @Param("runId") long runId,
                  @Param("status") String status,
                  @Param("fetchedCount") int fetchedCount,
                  @Param("createdCount") int createdCount,
                  @Param("skippedExistingCount") int skippedExistingCount,
                  @Param("mobileConflictCount") int mobileConflictCount,
                  @Param("failedCount") int failedCount,
                  @Param("hasMore") boolean hasMore,
                  @Param("cursorAfterJson") String cursorAfterJson,
                  @Param("warningsJson") String warningsJson,
                  @Param("errorMasked") String errorMasked,
                  @Param("finishedAt") Instant finishedAt);

    YouzanCustomerSyncRun selectRun(@Param("tenantId") long tenantId, @Param("runId") long runId);

    List<YouzanCustomerSyncRun> selectRuns(@Param("tenantId") long tenantId,
                                           @Param("shopId") String shopId,
                                           @Param("sourceMode") String sourceMode,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    long countRuns(@Param("tenantId") long tenantId,
                   @Param("shopId") String shopId,
                   @Param("sourceMode") String sourceMode);

    int insertItem(YouzanCustomerSyncItem item);

    List<YouzanCustomerSyncItem> selectItems(@Param("tenantId") long tenantId,
                                             @Param("runId") long runId,
                                             @Param("outcome") String outcome,
                                             @Param("offset") int offset,
                                             @Param("size") int size);

    long countItems(@Param("tenantId") long tenantId,
                    @Param("runId") long runId,
                    @Param("outcome") String outcome);

    class RunInsertRow {
        private Long id;
        private long tenantId;
        private String shopId;
        private String sourceMode;
        private String pullMode;
        private Integer requestedLimit;
        private long ownerStaffId;
        private String adapterName;
        private String status;
        private Instant leaseUntil;
        private long createdBy;
        private Instant startedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getTenantId() {
            return tenantId;
        }

        public void setTenantId(long tenantId) {
            this.tenantId = tenantId;
        }

        public String getShopId() {
            return shopId;
        }

        public void setShopId(String shopId) {
            this.shopId = shopId;
        }

        public String getSourceMode() {
            return sourceMode;
        }

        public void setSourceMode(String sourceMode) {
            this.sourceMode = sourceMode;
        }

        public String getPullMode() {
            return pullMode;
        }

        public void setPullMode(String pullMode) {
            this.pullMode = pullMode;
        }

        public Integer getRequestedLimit() {
            return requestedLimit;
        }

        public void setRequestedLimit(Integer requestedLimit) {
            this.requestedLimit = requestedLimit;
        }

        public long getOwnerStaffId() {
            return ownerStaffId;
        }

        public void setOwnerStaffId(long ownerStaffId) {
            this.ownerStaffId = ownerStaffId;
        }

        public String getAdapterName() {
            return adapterName;
        }

        public void setAdapterName(String adapterName) {
            this.adapterName = adapterName;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public Instant getLeaseUntil() {
            return leaseUntil;
        }

        public void setLeaseUntil(Instant leaseUntil) {
            this.leaseUntil = leaseUntil;
        }

        public long getCreatedBy() {
            return createdBy;
        }

        public void setCreatedBy(long createdBy) {
            this.createdBy = createdBy;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
