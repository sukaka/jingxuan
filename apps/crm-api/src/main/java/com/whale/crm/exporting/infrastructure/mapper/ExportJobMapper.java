package com.whale.crm.exporting.infrastructure.mapper;

import com.whale.crm.exporting.domain.ExportAccessLog;
import com.whale.crm.exporting.domain.ExportJob;
import com.whale.crm.exporting.domain.ExportJobRepository;
import com.whale.crm.exporting.domain.ExportStatus;
import com.whale.crm.exporting.domain.ExportType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ExportJobMapper extends ExportJobRepository {
    @Override void insert(@Param("e") ExportJob job);
    @Override Optional<ExportJob> findById(@Param("tenantId") long tenantId, @Param("id") long id);
    @Override Optional<ExportJob> findVisibleById(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("visibilityScope") String visibilityScope, @Param("selfId") long selfId);
    @Override Optional<ExportJob> findByIdempotency(@Param("tenantId") long tenantId,
            @Param("requestedBy") long requestedBy, @Param("key") String key);
    @Override List<ExportJob> search(@Param("tenantId") long tenantId, @Param("exportType") ExportType exportType,
            @Param("status") ExportStatus status, @Param("visibilityScope") String visibilityScope,
            @Param("selfId") long selfId, @Param("offset") int offset, @Param("size") int size);
    @Override long countSearch(@Param("tenantId") long tenantId, @Param("exportType") ExportType exportType,
            @Param("status") ExportStatus status, @Param("visibilityScope") String visibilityScope,
            @Param("selfId") long selfId);
    @Override List<Long> claimTenantIds(@Param("now") Instant now);
    @Override List<ExportJob> claimable(@Param("tenantId") long tenantId, @Param("now") Instant now,
            @Param("limit") int limit);
    @Override int claim(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
            @Param("now") Instant now, @Param("until") Instant until);
    @Override int renewLease(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("owner") String owner, @Param("until") Instant until);
    @Override int markSucceeded(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
            @Param("fileId") long fileId, @Param("rowCount") long rowCount,
            @Param("expiresAt") Instant expiresAt, @Param("scopeJson") String scopeJson,
            @Param("finishedAt") Instant finishedAt);
    @Override int markFailed(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
            @Param("error") String error, @Param("finishedAt") Instant finishedAt);
    @Override void insertAccessLog(@Param("e") ExportAccessLog log);
    @Override List<ExportAccessLog> searchAccessLogs(@Param("tenantId") long tenantId,
            @Param("jobId") Long jobId, @Param("actorId") Long actorId, @Param("action") String action,
            @Param("result") String result, @Param("visibilityScope") String visibilityScope,
            @Param("selfId") long selfId, @Param("offset") int offset, @Param("size") int size);
    @Override long countAccessLogs(@Param("tenantId") long tenantId, @Param("jobId") Long jobId,
            @Param("actorId") Long actorId, @Param("action") String action, @Param("result") String result,
            @Param("visibilityScope") String visibilityScope, @Param("selfId") long selfId);
}
