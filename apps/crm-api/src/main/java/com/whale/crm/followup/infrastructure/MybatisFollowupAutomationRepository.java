package com.whale.crm.followup.infrastructure;

import com.whale.crm.followup.domain.DueFollowupTask;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import com.whale.crm.followup.domain.FollowupBatchItem;
import com.whale.crm.followup.domain.FollowupCandidate;
import com.whale.crm.followup.domain.FollowupPolicy;
import com.whale.crm.followup.domain.FollowupScheduleJob;
import com.whale.crm.followup.infrastructure.mapper.FollowupAutomationMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisFollowupAutomationRepository implements FollowupAutomationRepository {
    private final FollowupAutomationMapper mapper;

    public MybatisFollowupAutomationRepository(FollowupAutomationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PolicyRow> findPolicy(long tenantId) {
        return Optional.ofNullable(mapper.selectPolicy(tenantId));
    }

    @Override
    public List<FollowupPolicy.IntentOverride> findIntentOverrides(long tenantId) {
        return mapper.selectIntentOverrides(tenantId);
    }

    @Override
    public void insertPolicy(long tenantId, int defaultIntervalDays, long actorUserId) {
        mapper.insertPolicy(tenantId, defaultIntervalDays, actorUserId);
    }

    @Override
    public int updatePolicy(long tenantId, int defaultIntervalDays, long actorUserId, long expectedVersion) {
        return mapper.updatePolicy(tenantId, defaultIntervalDays, actorUserId, expectedVersion);
    }

    @Override
    public void replaceIntentOverrides(long tenantId, List<FollowupPolicy.IntentOverride> overrides) {
        mapper.deleteIntentOverrides(tenantId);
        overrides.forEach(override -> mapper.insertIntentOverride(tenantId, override));
    }

    @Override
    public List<CandidateRow> findCandidates(long tenantId, LocalDateTime from, LocalDateTime toExclusive,
                                             String scopeType, List<Long> teamIds, long selfId,
                                             Integer offset, Integer size) {
        return mapper.selectCandidates(tenantId, from, toExclusive, scopeType, teamIds, selfId, offset, size);
    }

    @Override
    public long countCandidates(long tenantId, LocalDateTime from, LocalDateTime toExclusive,
                                String scopeType, List<Long> teamIds, long selfId) {
        return mapper.countCandidates(tenantId, from, toExclusive, scopeType, teamIds, selfId);
    }

    @Override
    public Optional<FollowupBatch> findBatchById(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectBatchById(tenantId, id));
    }

    @Override
    public Optional<FollowupBatch> findBatchByMonth(long tenantId, String orderMonth) {
        return Optional.ofNullable(mapper.selectBatchByMonth(tenantId, orderMonth));
    }

    @Override
    public void insertBatch(long tenantId, String orderMonth, long actorUserId) {
        mapper.insertBatch(tenantId, orderMonth, actorUserId);
    }

    @Override
    public List<Long> findRetryableBatchTenantIds(Instant now) {
        return mapper.selectRetryableBatchTenantIds(now);
    }

    @Override
    public List<FollowupBatch> findRetryableBatches(long tenantId, Instant now, int limit) {
        return mapper.selectRetryableBatches(tenantId, now, limit);
    }

    @Override
    public int claimBatch(long tenantId, long id, long expectedVersion, String expectedStatus,
                          String leaseOwner, Instant now, Instant leaseExpiresAt) {
        return mapper.claimBatch(tenantId, id, expectedVersion, expectedStatus, leaseOwner, now, leaseExpiresAt);
    }

    @Override
    public int finishBatch(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                           String errorMasked, Instant nextRetryAt, Instant finishedAt, int failureIncrement) {
        return mapper.finishBatch(tenantId, id, expectedVersion, leaseOwner, status, errorMasked,
                nextRetryAt, finishedAt, failureIncrement);
    }

    @Override
    public void insertAttempt(long tenantId, long batchId, int attemptNo, String status,
                              long actorUserId, String errorMasked) {
        mapper.insertAttempt(tenantId, batchId, attemptNo, status, actorUserId, errorMasked);
    }

    @Override
    public void insertItem(long tenantId, long batchId, FollowupCandidate candidate) {
        mapper.insertItem(tenantId, batchId, candidate);
    }

    @Override
    public Optional<FollowupBatchItem> findItemByCustomer(long tenantId, long batchId, long customerId) {
        return Optional.ofNullable(mapper.selectItemByCustomer(tenantId, batchId, customerId));
    }

    @Override
    public Optional<FollowupBatchItem> findItemForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectItemForUpdate(tenantId, id));
    }

    @Override
    public List<FollowupBatchItem> findItems(long tenantId, long batchId) {
        return mapper.selectItems(tenantId, batchId);
    }

    @Override
    public int updateItemStatus(long tenantId, long id, long expectedVersion, String status, Long taskId, String reason) {
        return mapper.updateItemStatus(tenantId, id, expectedVersion, status, taskId, reason);
    }

    @Override
    public void insertBatchOrder(long tenantId, long batchId, long itemId, long orderId) {
        mapper.insertBatchOrder(tenantId, batchId, itemId, orderId);
    }

    @Override
    public List<Long> findOrderIds(long tenantId, long batchId, long itemId) {
        return mapper.selectOrderIds(tenantId, batchId, itemId);
    }

    @Override
    public List<Long> findDueTaskTenantIds(Instant now) {
        return mapper.selectDueTaskTenantIds(now);
    }

    @Override
    public List<DueFollowupTask> findDueTasks(long tenantId, Instant now, int limit) {
        return mapper.selectDueTasks(tenantId, now, limit);
    }

    @Override
    public void insertScheduleJobIgnore(long tenantId, String jobType, String jobKey) {
        mapper.insertScheduleJobIgnore(tenantId, jobType, jobKey);
    }

    @Override
    public Optional<FollowupScheduleJob> findScheduleJob(long tenantId, String jobType, String jobKey) {
        return Optional.ofNullable(mapper.selectScheduleJob(tenantId, jobType, jobKey));
    }

    @Override
    public int claimScheduleJob(long tenantId, long id, long expectedVersion, String leaseOwner,
                                Instant now, Instant leaseExpiresAt) {
        return mapper.claimScheduleJob(tenantId, id, expectedVersion, leaseOwner, now, leaseExpiresAt);
    }

    @Override
    public int finishScheduleJob(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                                 String errorMasked, Instant nextRetryAt, int failureIncrement) {
        return mapper.finishScheduleJob(tenantId, id, expectedVersion, leaseOwner, status,
                errorMasked, nextRetryAt, failureIncrement);
    }
}
