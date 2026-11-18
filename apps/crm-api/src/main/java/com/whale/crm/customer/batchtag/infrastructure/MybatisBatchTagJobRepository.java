package com.whale.crm.customer.batchtag.infrastructure;

import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobCounts;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItemAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import com.whale.crm.customer.batchtag.domain.BatchTagPreview;
import com.whale.crm.customer.batchtag.domain.BatchTagPreviewMember;
import com.whale.crm.customer.batchtag.infrastructure.mapper.BatchTagJobMapper;
import com.whale.crm.customer.domain.CustomerListRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisBatchTagJobRepository implements BatchTagJobRepository {
    private final BatchTagJobMapper mapper;

    public MybatisBatchTagJobRepository(BatchTagJobMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insertPreview(BatchTagPreview preview) {
        mapper.insertPreview(preview);
    }

    @Override
    public Optional<BatchTagPreview> findPreviewById(long tenantId, String id) {
        return Optional.ofNullable(mapper.selectPreviewById(tenantId, id));
    }

    @Override
    public void insertPreviewMember(BatchTagPreviewMember member) {
        mapper.insertPreviewMember(member);
    }

    @Override
    public List<BatchTagPreviewMember> findPreviewMembers(long tenantId, String previewId,
                                                          Integer offset, Integer size) {
        return mapper.selectPreviewMembers(tenantId, previewId, offset, size);
    }

    @Override
    public void insertJob(BatchTagJob job) {
        mapper.insertJob(job);
    }

    @Override
    public Optional<BatchTagJob> findJobById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectJobById(tenantId, id));
    }

    @Override
    public Optional<BatchTagJob> findJobByNo(long tenantId, String jobNo) {
        return Optional.ofNullable(mapper.selectJobByNo(tenantId, jobNo));
    }

    @Override
    public Optional<BatchTagJob> findJobByPreviewId(long tenantId, String previewId) {
        return Optional.ofNullable(mapper.selectJobByPreviewId(tenantId, previewId));
    }

    @Override
    public Optional<BatchTagJob> findJobByRollbackOf(long tenantId, long rollbackOfJobId) {
        return Optional.ofNullable(mapper.selectJobByRollbackOf(tenantId, rollbackOfJobId));
    }

    @Override
    public List<BatchTagJob> searchJobs(long tenantId, String status, String scopeType, long requestedBy,
                                        int offset, int size) {
        return mapper.searchJobs(tenantId, status, scopeType, requestedBy, offset, size);
    }

    @Override
    public long countJobs(long tenantId, String status, String scopeType, long requestedBy) {
        return mapper.countJobs(tenantId, status, scopeType, requestedBy);
    }

    @Override
    public int claimJob(long tenantId, long id, long expectedVersion, String expectedStatus,
                        String leaseOwner, Instant now, Instant leaseExpiresAt) {
        return mapper.claimJob(tenantId, id, expectedVersion, expectedStatus, leaseOwner, now, leaseExpiresAt);
    }

    @Override
    public int renewLease(long tenantId, long id, long expectedVersion, String leaseOwner, Instant leaseExpiresAt) {
        return mapper.renewLease(tenantId, id, expectedVersion, leaseOwner, leaseExpiresAt);
    }

    @Override
    public int finishJob(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                         BatchTagJobCounts counts, String errorMasked, Instant finishedAt) {
        return mapper.finishJob(tenantId, id, expectedVersion, leaseOwner, status, counts, errorMasked, finishedAt);
    }

    @Override
    public List<Long> findExpiredJobTenantIds(Instant now) {
        return mapper.selectExpiredJobTenantIds(now);
    }

    @Override
    public List<BatchTagJob> findExpiredJobs(long tenantId, Instant now, int limit) {
        return mapper.selectExpiredJobs(tenantId, now, limit);
    }

    @Override
    public void insertJobAttempt(BatchTagJobAttempt attempt) {
        mapper.insertJobAttempt(attempt);
    }

    @Override
    public void insertItem(BatchTagJobItem item) {
        mapper.insertItem(item);
    }

    @Override
    public List<BatchTagJobItem> findWorkItems(long tenantId, long jobId) {
        return mapper.selectWorkItems(tenantId, jobId);
    }

    @Override
    public List<BatchTagJobItem> findItemsPage(long tenantId, long jobId, int offset, int size) {
        return mapper.selectItemsPage(tenantId, jobId, offset, size);
    }

    @Override
    public long countItems(long tenantId, long jobId) {
        return mapper.countItems(tenantId, jobId);
    }

    @Override
    public Optional<BatchTagJobItem> findItemForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectItemForUpdate(tenantId, id));
    }

    @Override
    public List<BatchTagJobItem> findRollbackCandidates(long tenantId, long originalJobId) {
        return mapper.selectRollbackCandidates(tenantId, originalJobId);
    }

    @Override
    public int updateItemResult(long tenantId, long id, long expectedVersion, String status, String failureCode,
                                String reason, String beforeTagCodesJson, String afterTagCodesJson,
                                String changedTagCodesJson, Instant processedAt) {
        return mapper.updateItemResult(tenantId, id, expectedVersion, status, failureCode, reason,
                beforeTagCodesJson, afterTagCodesJson, changedTagCodesJson, processedAt);
    }

    @Override
    public void insertItemAttempt(BatchTagJobItemAttempt attempt) {
        mapper.insertItemAttempt(attempt);
    }

    @Override
    public BatchTagJobCounts countItemResults(long tenantId, long jobId) {
        return mapper.countItemResults(tenantId, jobId);
    }

    @Override
    public Optional<Long> findConfirmedMergeTarget(long tenantId, long secondaryCustomerId) {
        return Optional.ofNullable(mapper.selectConfirmedMergeTarget(tenantId, secondaryCustomerId));
    }

    @Override
    public List<CustomerListRow> findOperationBatchMembers(long tenantId, long batchId, String scopeType,
                                                           List<Long> teamIds, long selfId,
                                                           Integer offset, Integer size) {
        return mapper.selectOperationBatchMembers(tenantId, batchId, scopeType, teamIds, selfId, offset, size);
    }

    @Override
    public long countOperationBatchMembers(long tenantId, long batchId, String scopeType,
                                           List<Long> teamIds, long selfId) {
        return mapper.countOperationBatchMembers(tenantId, batchId, scopeType, teamIds, selfId);
    }
}
