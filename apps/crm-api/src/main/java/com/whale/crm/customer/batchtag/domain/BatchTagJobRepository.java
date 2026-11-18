package com.whale.crm.customer.batchtag.domain;

import com.whale.crm.customer.domain.CustomerListRow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BatchTagJobRepository {
    void insertPreview(BatchTagPreview preview);

    Optional<BatchTagPreview> findPreviewById(long tenantId, String id);

    void insertPreviewMember(BatchTagPreviewMember member);

    List<BatchTagPreviewMember> findPreviewMembers(long tenantId, String previewId,
                                                   Integer offset, Integer size);

    void insertJob(BatchTagJob job);

    Optional<BatchTagJob> findJobById(long tenantId, long id);

    Optional<BatchTagJob> findJobByNo(long tenantId, String jobNo);

    Optional<BatchTagJob> findJobByPreviewId(long tenantId, String previewId);

    Optional<BatchTagJob> findJobByRollbackOf(long tenantId, long rollbackOfJobId);

    List<BatchTagJob> searchJobs(long tenantId, String status, String scopeType, long requestedBy,
                                 int offset, int size);

    long countJobs(long tenantId, String status, String scopeType, long requestedBy);

    int claimJob(long tenantId, long id, long expectedVersion, String expectedStatus,
                 String leaseOwner, Instant now, Instant leaseExpiresAt);

    int renewLease(long tenantId, long id, long expectedVersion, String leaseOwner, Instant leaseExpiresAt);

    int finishJob(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                  BatchTagJobCounts counts, String errorMasked, Instant finishedAt);

    List<Long> findExpiredJobTenantIds(Instant now);

    List<BatchTagJob> findExpiredJobs(long tenantId, Instant now, int limit);

    void insertJobAttempt(BatchTagJobAttempt attempt);

    void insertItem(BatchTagJobItem item);

    List<BatchTagJobItem> findWorkItems(long tenantId, long jobId);

    List<BatchTagJobItem> findItemsPage(long tenantId, long jobId, int offset, int size);

    long countItems(long tenantId, long jobId);

    Optional<BatchTagJobItem> findItemForUpdate(long tenantId, long id);

    List<BatchTagJobItem> findRollbackCandidates(long tenantId, long originalJobId);

    int updateItemResult(long tenantId, long id, long expectedVersion, String status, String failureCode,
                         String reason, String beforeTagCodesJson, String afterTagCodesJson,
                         String changedTagCodesJson, Instant processedAt);

    void insertItemAttempt(BatchTagJobItemAttempt attempt);

    BatchTagJobCounts countItemResults(long tenantId, long jobId);

    Optional<Long> findConfirmedMergeTarget(long tenantId, long secondaryCustomerId);

    List<CustomerListRow> findOperationBatchMembers(long tenantId, long batchId, String scopeType,
                                                    List<Long> teamIds, long selfId,
                                                    Integer offset, Integer size);

    long countOperationBatchMembers(long tenantId, long batchId, String scopeType,
                                    List<Long> teamIds, long selfId);
}
