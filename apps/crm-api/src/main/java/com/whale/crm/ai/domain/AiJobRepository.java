package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiJobRepository {
    void insert(AiJob job);
    Optional<AiJob> findById(long tenantId, long id);
    Optional<AiJob> findByIdForUpdate(long tenantId, long id);
    Optional<AiJob> findByIdempotency(long tenantId, long requestedBy, String key);
    Optional<AiJob> findScopedById(long tenantId, long id, String scopeType, List<Long> teamIds, long selfId);
    List<AiJob> search(long tenantId, String conversationId, String status, String scopeType, List<Long> teamIds, long selfId, int offset, int size);
    long countSearch(long tenantId, String conversationId, String status, String scopeType, List<Long> teamIds, long selfId);
    List<AiJob> claimable(long tenantId, Instant now, int limit);
    List<Long> claimTenantIds(Instant now);
    int claim(long tenantId, long id, long version, String owner, Instant now, Instant until);
    int markProposed(long tenantId, long id, long version, String provider, String model);
    int markRetry(long tenantId, long id, long version, String error, Instant nextRetryAt, boolean terminal);
    int confirm(long tenantId, long id, long version, String status, long actorUserId, Instant now, String reason, String key, String hash, Long taskId);
    void insertInsight(AiInsight insight);
    Optional<AiInsight> findInsight(long tenantId, long jobId);
    AiQualityCount qualityCount(long tenantId, String scopeType, List<Long> teamIds, long selfId);

    record AiQualityCount(long pendingReview, long riskSuggested, long failed, long confirmed) { }
}
