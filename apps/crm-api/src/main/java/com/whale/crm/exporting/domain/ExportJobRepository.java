package com.whale.crm.exporting.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExportJobRepository {
    void insert(ExportJob job);

    Optional<ExportJob> findById(long tenantId, long id);

    Optional<ExportJob> findVisibleById(long tenantId, long id, String visibilityScope, long selfId);

    Optional<ExportJob> findByIdempotency(long tenantId, long requestedBy, String key);

    List<ExportJob> search(long tenantId, ExportType exportType, ExportStatus status,
                           String visibilityScope, long selfId, int offset, int size);

    long countSearch(long tenantId, ExportType exportType, ExportStatus status,
                     String visibilityScope, long selfId);

    List<Long> claimTenantIds(Instant now);

    List<ExportJob> claimable(long tenantId, Instant now, int limit);

    int claim(long tenantId, long id, long expectedVersion, String owner, Instant now, Instant until);

    int renewLease(long tenantId, long id, String owner, Instant until);

    int markSucceeded(long tenantId, long id, long expectedVersion, String owner, long fileId,
                      long rowCount, Instant expiresAt, String scopeJson, Instant finishedAt);

    int markFailed(long tenantId, long id, long expectedVersion, String owner,
                   String error, Instant finishedAt);

    void insertAccessLog(ExportAccessLog log);

    List<ExportAccessLog> searchAccessLogs(long tenantId, Long jobId, Long actorId, String action,
                                           String result, String visibilityScope, long selfId, int offset, int size);

    long countAccessLogs(long tenantId, Long jobId, Long actorId, String action,
                         String result, String visibilityScope, long selfId);
}
