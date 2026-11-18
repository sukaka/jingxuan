package com.whale.crm.followup.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface FollowupAutomationRepository {
    Optional<PolicyRow> findPolicy(long tenantId);

    List<FollowupPolicy.IntentOverride> findIntentOverrides(long tenantId);

    void insertPolicy(long tenantId, int defaultIntervalDays, long actorUserId);

    int updatePolicy(long tenantId, int defaultIntervalDays, long actorUserId, long expectedVersion);

    void replaceIntentOverrides(long tenantId, List<FollowupPolicy.IntentOverride> overrides);

    List<CandidateRow> findCandidates(long tenantId, LocalDateTime from, LocalDateTime toExclusive,
                                      String scopeType, List<Long> teamIds, long selfId,
                                      Integer offset, Integer size);

    long countCandidates(long tenantId, LocalDateTime from, LocalDateTime toExclusive,
                         String scopeType, List<Long> teamIds, long selfId);

    Optional<FollowupBatch> findBatchById(long tenantId, long id);

    Optional<FollowupBatch> findBatchByMonth(long tenantId, String orderMonth);

    void insertBatch(long tenantId, String orderMonth, long actorUserId);

    List<Long> findRetryableBatchTenantIds(Instant now);

    List<FollowupBatch> findRetryableBatches(long tenantId, Instant now, int limit);

    int claimBatch(long tenantId, long id, long expectedVersion, String expectedStatus,
                   String leaseOwner, Instant now, Instant leaseExpiresAt);

    int finishBatch(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                    String errorMasked, Instant nextRetryAt, Instant finishedAt, int failureIncrement);

    void insertAttempt(long tenantId, long batchId, int attemptNo, String status,
                       long actorUserId, String errorMasked);

    void insertItem(long tenantId, long batchId, FollowupCandidate candidate);

    Optional<FollowupBatchItem> findItemByCustomer(long tenantId, long batchId, long customerId);

    Optional<FollowupBatchItem> findItemForUpdate(long tenantId, long id);

    List<FollowupBatchItem> findItems(long tenantId, long batchId);

    int updateItemStatus(long tenantId, long id, long expectedVersion, String status, Long taskId, String reason);

    void insertBatchOrder(long tenantId, long batchId, long itemId, long orderId);

    List<Long> findOrderIds(long tenantId, long batchId, long itemId);

    List<Long> findDueTaskTenantIds(Instant now);

    List<DueFollowupTask> findDueTasks(long tenantId, Instant now, int limit);

    void insertScheduleJobIgnore(long tenantId, String jobType, String jobKey);

    Optional<FollowupScheduleJob> findScheduleJob(long tenantId, String jobType, String jobKey);

    int claimScheduleJob(long tenantId, long id, long expectedVersion, String leaseOwner,
                         Instant now, Instant leaseExpiresAt);

    int finishScheduleJob(long tenantId, long id, long expectedVersion, String leaseOwner, String status,
                          String errorMasked, Instant nextRetryAt, int failureIncrement);

    record PolicyRow(long tenantId, int defaultIntervalDays, long version) {
    }

    record CandidateRow(long customerId, String customerName, long ownerStaffId, Long teamId,
                        Instant firstOrderedAt, String orderIds) {
    }
}
