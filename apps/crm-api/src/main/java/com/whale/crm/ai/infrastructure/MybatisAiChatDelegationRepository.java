package com.whale.crm.ai.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import com.whale.crm.ai.infrastructure.mapper.AiChatDelegationMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisAiChatDelegationRepository implements AiChatDelegationRepository {
    private final AiChatDelegationMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisAiChatDelegationRepository(AiChatDelegationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AiChatDelegationPolicy> findPolicy(long tenantId) {
        PolicyRow row = mapper.selectPolicy(tenantId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AiChatDelegationPolicy(row.tenantId(), row.enabled(), row.emergencyStopped(),
                row.minIntervalSeconds(), row.dailyLimit(), row.allowedStart(), row.allowedEnd(), row.zoneId(),
                readTerms(row.blockedTermsJson()), row.version(), mapper.selectScopes(tenantId)));
    }

    @Override
    public Optional<AiChatDelegationPolicy> findPolicyForUpdate(long tenantId) {
        PolicyRow row = mapper.selectPolicyForUpdate(tenantId);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AiChatDelegationPolicy(row.tenantId(), row.enabled(), row.emergencyStopped(),
                row.minIntervalSeconds(), row.dailyLimit(), row.allowedStart(), row.allowedEnd(), row.zoneId(),
                readTerms(row.blockedTermsJson()), row.version(), mapper.selectScopes(tenantId)));
    }

    @Override
    public void insertPolicy(AiChatDelegationPolicy policy, long actorUserId) {
        mapper.insertPolicy(policy, json(policy.blockedTerms()), actorUserId);
    }

    @Override
    public int updatePolicy(AiChatDelegationPolicy policy, long actorUserId, long expectedVersion) {
        return mapper.updatePolicy(policy, json(policy.blockedTerms()), actorUserId, expectedVersion);
    }

    @Override
    public void replaceScopes(long tenantId, List<AiChatDelegationPolicy.ScopeOverride> scopes) {
        mapper.deleteScopes(tenantId);
        scopes.forEach(scope -> mapper.insertScope(tenantId, scope));
    }

    @Override
    public Optional<AiChatDelegationCustomerControl> findCustomerControl(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectCustomerControl(tenantId, customerId));
    }

    @Override
    public void insertCustomerControl(long tenantId, long customerId, String mode, String reason, long actorUserId) {
        mapper.insertCustomerControl(tenantId, customerId, mode, reason, actorUserId);
    }

    @Override
    public int updateCustomerControl(long tenantId, long customerId, String mode, String reason,
                                     long actorUserId, long expectedVersion) {
        return mapper.updateCustomerControl(tenantId, customerId, mode, reason, actorUserId, expectedVersion);
    }

    @Override
    public List<AiChatDelegationRun> findPendingForCustomerForUpdate(long tenantId, long customerId) {
        return mapper.selectPendingForCustomerForUpdate(tenantId, customerId);
    }

    @Override
    public int blockPendingForCustomer(long tenantId, long customerId, String reason) {
        return mapper.blockPendingForCustomer(tenantId, customerId, reason, Instant.now());
    }

    @Override
    public void insertRun(AiChatDelegationRun run) {
        mapper.insertRun(run);
    }

    @Override
    public Optional<AiChatDelegationRun> findRunByMessage(long tenantId, long sourceMessageId) {
        return Optional.ofNullable(mapper.selectRunByMessage(tenantId, sourceMessageId));
    }

    @Override
    public Optional<AiChatDelegationRun> findRun(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRun(tenantId, id));
    }

    @Override
    public Optional<AiChatDelegationRun> findRunForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectRunForUpdate(tenantId, id));
    }

    @Override
    public List<Long> findWorkTenantIds(Instant now) {
        return mapper.selectWorkTenantIds(now);
    }

    @Override
    public List<AiChatDelegationRun> findClaimableGeneration(long tenantId, Instant now, int limit) {
        return mapper.selectClaimableGeneration(tenantId, now, limit);
    }

    @Override
    public List<AiChatDelegationRun> findClaimableSending(long tenantId, Instant now, int limit) {
        return mapper.selectClaimableSending(tenantId, now, limit);
    }

    @Override
    public List<AiChatDelegationRun> findExpiredSending(Instant now, int limit) {
        return mapper.selectExpiredSending(now, limit);
    }

    @Override
    public int claimGeneration(long tenantId, long id, long expectedVersion, String owner,
                               Instant now, Instant leaseExpiresAt) {
        return mapper.claimGeneration(tenantId, id, expectedVersion, owner, now, leaseExpiresAt);
    }

    @Override
    public int claimSending(long tenantId, long id, long expectedVersion, String owner,
                            Instant now, Instant leaseExpiresAt) {
        return mapper.claimSending(tenantId, id, expectedVersion, owner, now, leaseExpiresAt);
    }

    @Override
    public int markGenerated(long tenantId, long id, long expectedVersion, String owner, String replyCipher,
                             String intent, String riskFlagsJson, String aiProvider, String aiModel, Instant now) {
        return mapper.markGenerated(tenantId, id, expectedVersion, owner, replyCipher, intent, riskFlagsJson,
                aiProvider, aiModel, now);
    }

    @Override
    public int updateRunStatus(long tenantId, long id, long expectedVersion, String expectedStatus,
                               String targetStatus, String reasonMasked, Instant nextAttemptAt, Instant now) {
        return mapper.updateRunStatus(tenantId, id, expectedVersion, expectedStatus, targetStatus,
                reasonMasked, nextAttemptAt, now);
    }

    @Override
    public int markSendAttempted(long tenantId, long id, long expectedVersion, String owner, Instant now) {
        return mapper.markSendAttempted(tenantId, id, expectedVersion, owner, now);
    }

    @Override
    public int finishSend(long tenantId, long id, long expectedVersion, String owner, String status,
                          String reasonMasked, String provider, String providerMessageId,
                          Instant nextAttemptAt, Instant now) {
        return mapper.finishSend(tenantId, id, expectedVersion, owner, status, reasonMasked, provider,
                providerMessageId, nextAttemptAt, now);
    }

    @Override
    public Optional<Instant> findLatestSendAttempt(long tenantId, long customerId) {
        return Optional.ofNullable(mapper.selectLatestSendAttempt(tenantId, customerId));
    }

    @Override
    public long countSendAttemptsSince(long tenantId, long customerId, Instant since) {
        return mapper.countSendAttemptsSince(tenantId, customerId, since);
    }

    @Override
    public void insertAttempt(AiChatDelegationAttempt attempt) {
        mapper.insertAttempt(attempt);
    }

    @Override
    public List<AiChatDelegationRun> search(long tenantId, Long customerId, String status, String scopeType,
                                            List<Long> teamIds, long selfId, int offset, int size) {
        return mapper.search(tenantId, customerId, status, scopeType, teamIds, selfId, offset, size);
    }

    @Override
    public long countSearch(long tenantId, Long customerId, String status, String scopeType,
                            List<Long> teamIds, long selfId) {
        return mapper.countSearch(tenantId, customerId, status, scopeType, teamIds, selfId);
    }

    @Override
    public List<StatusCount> countByStatus(long tenantId, String scopeType, List<Long> teamIds, long selfId) {
        return mapper.countByStatus(tenantId, scopeType, teamIds, selfId);
    }

    private List<String> readTerms(String value) {
        try {
            return objectMapper.readValue(value, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            throw new IllegalStateException("AI托管禁发词配置损坏", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI托管配置序列化失败", ex);
        }
    }
}
