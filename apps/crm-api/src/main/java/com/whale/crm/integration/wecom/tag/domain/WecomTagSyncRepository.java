package com.whale.crm.integration.wecom.tag.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 企微标签双向同步仓储接口。
 */
public interface WecomTagSyncRepository {

    List<WecomTagMapping> findMappings(long tenantId, String corpId);

    Optional<WecomTagMapping> findMappingByCrmTagCode(long tenantId, String corpId, String crmTagCode);

    void upsertMapping(long tenantId, String corpId, String crmTagCode, String wecomGroupId, String wecomGroupName,
                       String wecomTagId, String wecomTagName, String syncStatus, Instant lastSyncedAt,
                       Long actorUserId);

    long startRun(long tenantId, String corpId, String syncScope, String direction, String triggerSource,
                  String adapterName, Long actorUserId, Instant now, Instant leaseUntil);

    int finishRun(long tenantId, long runId, String status, int pulledCount, int pushedCount, int skippedCount,
                  int failedCount, String errorMasked, Instant finishedAt);

    Optional<WecomTagSyncRun> findRunById(long tenantId, long runId);

    List<WecomTagSyncRun> findRecentRuns(long tenantId, String corpId, int limit);

    long insertPushTask(long tenantId, String corpId, long customerId, long wecomContactId, String wecomUserid,
                        String externalUserid, String addTagIdsJson, String removeTagIdsJson, int maxAttempts,
                        Instant nextRetryAt, String triggerSource, Long actorUserId);

    Optional<WecomCustomerTagPushTask> findPushTaskById(long tenantId, long taskId);

    List<WecomCustomerTagPushTask> findPushTasks(long tenantId, String corpId, String status, int limit);

    int recoverExpiredPushTasks(Instant now, String errorMasked);

    List<WecomCustomerTagPushTask> findPendingPushTasks(Instant now, int limit);

    int claimPushTask(long tenantId, long taskId, int expectedAttempts, Instant now, Instant leaseUntil);

    int finishPushTask(long tenantId, long taskId, int expectedAttempts, String status, Instant nextRetryAt,
                       String errorMasked);

    int resetPushTaskForRetry(long tenantId, long taskId, Instant now);

    List<WecomLinkedContactForTagSync> findLinkedContactsByCustomerId(long tenantId, String corpId, long customerId);

    List<WecomLinkedContactForTagSync> findLinkedContactsByExternalUserid(long tenantId, String corpId,
                                                                          String externalUserid);

    List<WecomLinkedContactForTagSync> findAllLinkedContacts(long tenantId, String corpId);

    int updateContactTagSnapshot(long tenantId, long wecomContactId, String tagSnapshotJson);
}
