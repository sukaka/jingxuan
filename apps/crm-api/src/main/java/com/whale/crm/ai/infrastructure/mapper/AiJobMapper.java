package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.AiInsight;
import com.whale.crm.ai.domain.AiJob;
import com.whale.crm.ai.domain.AiJobRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AiJobMapper extends AiJobRepository {
    @Override
    void insert(@Param("e") AiJob job);

    @Override
    Optional<AiJob> findById(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<AiJob> findByIdForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    @Override
    Optional<AiJob> findByIdempotency(@Param("tenantId") long tenantId, @Param("requestedBy") long requestedBy, @Param("key") String key);

    @Override
    Optional<AiJob> findScopedById(@Param("tenantId") long tenantId, @Param("id") long id, @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);

    @Override
    List<AiJob> search(@Param("tenantId") long tenantId, @Param("conversationId") String conversationId, @Param("status") String status, @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId, @Param("offset") int offset, @Param("size") int size);

    @Override
    long countSearch(@Param("tenantId") long tenantId, @Param("conversationId") String conversationId, @Param("status") String status, @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);

    @Override
    List<AiJob> claimable(@Param("tenantId") long tenantId, @Param("now") Instant now, @Param("limit") int limit);

    @Override
    List<Long> claimTenantIds(@Param("now") Instant now);

    @Override
    int claim(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version, @Param("owner") String owner, @Param("now") Instant now, @Param("until") Instant until);

    @Override
    int markProposed(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version, @Param("provider") String provider, @Param("model") String model);

    @Override
    int markRetry(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version, @Param("error") String error, @Param("nextRetryAt") Instant nextRetryAt, @Param("terminal") boolean terminal);

    @Override
    int confirm(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version, @Param("status") String status, @Param("actorUserId") long actorUserId, @Param("now") Instant now, @Param("reason") String reason, @Param("key") String key, @Param("hash") String hash, @Param("taskId") Long taskId);

    @Override
    void insertInsight(@Param("e") AiInsight insight);

    @Override
    Optional<AiInsight> findInsight(@Param("tenantId") long tenantId, @Param("jobId") long jobId);

    @Override
    AiQualityCount qualityCount(@Param("tenantId") long tenantId, @Param("scopeType") String scopeType, @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);
}
