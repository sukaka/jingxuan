package com.whale.crm.customer.batchtag.infrastructure.mapper;

import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobCounts;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItemAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagPreview;
import com.whale.crm.customer.batchtag.domain.BatchTagPreviewMember;
import com.whale.crm.customer.domain.CustomerListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface BatchTagJobMapper {
    int insertPreview(BatchTagPreview preview);

    BatchTagPreview selectPreviewById(@Param("tenantId") long tenantId, @Param("id") String id);

    int insertPreviewMember(BatchTagPreviewMember member);

    List<BatchTagPreviewMember> selectPreviewMembers(@Param("tenantId") long tenantId,
            @Param("previewId") String previewId, @Param("offset") Integer offset, @Param("size") Integer size);

    int insertJob(BatchTagJob job);

    BatchTagJob selectJobById(@Param("tenantId") long tenantId, @Param("id") long id);

    BatchTagJob selectJobByNo(@Param("tenantId") long tenantId, @Param("jobNo") String jobNo);

    BatchTagJob selectJobByPreviewId(@Param("tenantId") long tenantId, @Param("previewId") String previewId);

    BatchTagJob selectJobByRollbackOf(@Param("tenantId") long tenantId,
                                      @Param("rollbackOfJobId") long rollbackOfJobId);

    List<BatchTagJob> searchJobs(@Param("tenantId") long tenantId, @Param("status") String status,
            @Param("scopeType") String scopeType, @Param("requestedBy") long requestedBy,
            @Param("offset") int offset, @Param("size") int size);

    long countJobs(@Param("tenantId") long tenantId, @Param("status") String status,
            @Param("scopeType") String scopeType, @Param("requestedBy") long requestedBy);

    int claimJob(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
            @Param("leaseOwner") String leaseOwner, @Param("now") Instant now,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int renewLease(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("leaseOwner") String leaseOwner,
            @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int finishJob(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("leaseOwner") String leaseOwner,
            @Param("status") String status, @Param("counts") BatchTagJobCounts counts,
            @Param("errorMasked") String errorMasked, @Param("finishedAt") Instant finishedAt);

    List<Long> selectExpiredJobTenantIds(@Param("now") Instant now);

    List<BatchTagJob> selectExpiredJobs(@Param("tenantId") long tenantId, @Param("now") Instant now,
                                        @Param("limit") int limit);

    int insertJobAttempt(BatchTagJobAttempt attempt);

    int insertItem(BatchTagJobItem item);

    List<BatchTagJobItem> selectWorkItems(@Param("tenantId") long tenantId, @Param("jobId") long jobId);

    List<BatchTagJobItem> selectItemsPage(@Param("tenantId") long tenantId, @Param("jobId") long jobId,
            @Param("offset") int offset, @Param("size") int size);

    long countItems(@Param("tenantId") long tenantId, @Param("jobId") long jobId);

    BatchTagJobItem selectItemForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    List<BatchTagJobItem> selectRollbackCandidates(@Param("tenantId") long tenantId,
                                                    @Param("originalJobId") long originalJobId);

    int updateItemResult(@Param("tenantId") long tenantId, @Param("id") long id,
            @Param("expectedVersion") long expectedVersion, @Param("status") String status,
            @Param("failureCode") String failureCode, @Param("reason") String reason,
            @Param("beforeTagCodesJson") String beforeTagCodesJson,
            @Param("afterTagCodesJson") String afterTagCodesJson,
            @Param("changedTagCodesJson") String changedTagCodesJson, @Param("processedAt") Instant processedAt);

    int insertItemAttempt(BatchTagJobItemAttempt attempt);

    BatchTagJobCounts countItemResults(@Param("tenantId") long tenantId, @Param("jobId") long jobId);

    Long selectConfirmedMergeTarget(@Param("tenantId") long tenantId,
                                    @Param("secondaryCustomerId") long secondaryCustomerId);

    List<CustomerListRow> selectOperationBatchMembers(@Param("tenantId") long tenantId,
            @Param("batchId") long batchId, @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
            @Param("offset") Integer offset, @Param("size") Integer size);

    long countOperationBatchMembers(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
            @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds,
            @Param("selfId") long selfId);
}
