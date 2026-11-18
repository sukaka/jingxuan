package com.whale.crm.followup.infrastructure.mapper;

import com.whale.crm.followup.domain.DueFollowupTask;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import com.whale.crm.followup.domain.FollowupBatchItem;
import com.whale.crm.followup.domain.FollowupCandidate;
import com.whale.crm.followup.domain.FollowupPolicy;
import com.whale.crm.followup.domain.FollowupScheduleJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface FollowupAutomationMapper {
    FollowupAutomationRepository.PolicyRow selectPolicy(@Param("tenantId") long tenantId);

    List<FollowupPolicy.IntentOverride> selectIntentOverrides(@Param("tenantId") long tenantId);

    void insertPolicy(@Param("tenantId") long tenantId, @Param("defaultIntervalDays") int defaultIntervalDays,
                      @Param("actorUserId") long actorUserId);

    int updatePolicy(@Param("tenantId") long tenantId, @Param("defaultIntervalDays") int defaultIntervalDays,
                     @Param("actorUserId") long actorUserId, @Param("expectedVersion") long expectedVersion);

    void deleteIntentOverrides(@Param("tenantId") long tenantId);

    void insertIntentOverride(@Param("tenantId") long tenantId,
                              @Param("override") FollowupPolicy.IntentOverride override);

    List<FollowupAutomationRepository.CandidateRow> selectCandidates(
            @Param("tenantId") long tenantId, @Param("from") LocalDateTime from,
            @Param("toExclusive") LocalDateTime toExclusive, @Param("scopeType") String scopeType,
            @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
            @Param("offset") Integer offset, @Param("size") Integer size);

    long countCandidates(@Param("tenantId") long tenantId, @Param("from") LocalDateTime from,
                         @Param("toExclusive") LocalDateTime toExclusive, @Param("scopeType") String scopeType,
                         @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);

    FollowupBatch selectBatchById(@Param("tenantId") long tenantId, @Param("id") long id);

    FollowupBatch selectBatchByMonth(@Param("tenantId") long tenantId, @Param("orderMonth") String orderMonth);

    void insertBatch(@Param("tenantId") long tenantId, @Param("orderMonth") String orderMonth,
                     @Param("actorUserId") long actorUserId);

    List<Long> selectRetryableBatchTenantIds(@Param("now") Instant now);

    List<FollowupBatch> selectRetryableBatches(@Param("tenantId") long tenantId,
                                               @Param("now") Instant now, @Param("limit") int limit);

    int claimBatch(@Param("tenantId") long tenantId, @Param("id") long id,
                   @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
                   @Param("leaseOwner") String leaseOwner, @Param("now") Instant now,
                   @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int finishBatch(@Param("tenantId") long tenantId, @Param("id") long id,
                    @Param("expectedVersion") long expectedVersion, @Param("leaseOwner") String leaseOwner,
                    @Param("status") String status, @Param("errorMasked") String errorMasked,
                    @Param("nextRetryAt") Instant nextRetryAt, @Param("finishedAt") Instant finishedAt,
                    @Param("failureIncrement") int failureIncrement);

    void insertAttempt(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                       @Param("attemptNo") int attemptNo, @Param("status") String status,
                       @Param("actorUserId") long actorUserId, @Param("errorMasked") String errorMasked);

    void insertItem(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                    @Param("candidate") FollowupCandidate candidate);

    FollowupBatchItem selectItemByCustomer(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                                           @Param("customerId") long customerId);

    FollowupBatchItem selectItemForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    List<FollowupBatchItem> selectItems(@Param("tenantId") long tenantId, @Param("batchId") long batchId);

    int updateItemStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion, @Param("status") String status,
                         @Param("taskId") Long taskId, @Param("reason") String reason);

    void insertBatchOrder(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                          @Param("itemId") long itemId, @Param("orderId") long orderId);

    List<Long> selectOrderIds(@Param("tenantId") long tenantId, @Param("batchId") long batchId,
                              @Param("itemId") long itemId);

    List<Long> selectDueTaskTenantIds(@Param("now") Instant now);

    List<DueFollowupTask> selectDueTasks(@Param("tenantId") long tenantId,
                                         @Param("now") Instant now, @Param("limit") int limit);

    void insertScheduleJobIgnore(@Param("tenantId") long tenantId, @Param("jobType") String jobType,
                                 @Param("jobKey") String jobKey);

    FollowupScheduleJob selectScheduleJob(@Param("tenantId") long tenantId, @Param("jobType") String jobType,
                                          @Param("jobKey") String jobKey);

    int claimScheduleJob(@Param("tenantId") long tenantId, @Param("id") long id,
                         @Param("expectedVersion") long expectedVersion, @Param("leaseOwner") String leaseOwner,
                         @Param("now") Instant now, @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int finishScheduleJob(@Param("tenantId") long tenantId, @Param("id") long id,
                          @Param("expectedVersion") long expectedVersion, @Param("leaseOwner") String leaseOwner,
                          @Param("status") String status, @Param("errorMasked") String errorMasked,
                          @Param("nextRetryAt") Instant nextRetryAt, @Param("failureIncrement") int failureIncrement);
}
