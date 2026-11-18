package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiChatDelegationRepository {
    Optional<AiChatDelegationPolicy> findPolicy(long tenantId);

    Optional<AiChatDelegationPolicy> findPolicyForUpdate(long tenantId);

    void insertPolicy(AiChatDelegationPolicy policy, long actorUserId);

    int updatePolicy(AiChatDelegationPolicy policy, long actorUserId, long expectedVersion);

    void replaceScopes(long tenantId, List<AiChatDelegationPolicy.ScopeOverride> scopes);

    Optional<AiChatDelegationCustomerControl> findCustomerControl(long tenantId, long customerId);

    void insertCustomerControl(long tenantId, long customerId, String mode, String reason, long actorUserId);

    int updateCustomerControl(long tenantId, long customerId, String mode, String reason,
                              long actorUserId, long expectedVersion);

    List<AiChatDelegationRun> findPendingForCustomerForUpdate(long tenantId, long customerId);

    int blockPendingForCustomer(long tenantId, long customerId, String reason);

    void insertRun(AiChatDelegationRun run);

    Optional<AiChatDelegationRun> findRunByMessage(long tenantId, long sourceMessageId);

    Optional<AiChatDelegationRun> findRun(long tenantId, long id);

    Optional<AiChatDelegationRun> findRunForUpdate(long tenantId, long id);

    List<Long> findWorkTenantIds(Instant now);

    List<AiChatDelegationRun> findClaimableGeneration(long tenantId, Instant now, int limit);

    List<AiChatDelegationRun> findClaimableSending(long tenantId, Instant now, int limit);

    List<AiChatDelegationRun> findExpiredSending(Instant now, int limit);

    int claimGeneration(long tenantId, long id, long expectedVersion, String owner,
                        Instant now, Instant leaseExpiresAt);

    int claimSending(long tenantId, long id, long expectedVersion, String owner,
                     Instant now, Instant leaseExpiresAt);

    int markGenerated(long tenantId, long id, long expectedVersion, String owner, String replyCipher,
                      String intent, String riskFlagsJson, String aiProvider, String aiModel, Instant now);

    int updateRunStatus(long tenantId, long id, long expectedVersion, String expectedStatus,
                        String targetStatus, String reasonMasked, Instant nextAttemptAt, Instant now);

    int markSendAttempted(long tenantId, long id, long expectedVersion, String owner, Instant now);

    int finishSend(long tenantId, long id, long expectedVersion, String owner, String status,
                   String reasonMasked, String provider, String providerMessageId,
                   Instant nextAttemptAt, Instant now);

    Optional<Instant> findLatestSendAttempt(long tenantId, long customerId);

    long countSendAttemptsSince(long tenantId, long customerId, Instant since);

    void insertAttempt(AiChatDelegationAttempt attempt);

    List<AiChatDelegationRun> search(long tenantId, Long customerId, String status, String scopeType,
                                     List<Long> teamIds, long selfId, int offset, int size);

    long countSearch(long tenantId, Long customerId, String status, String scopeType,
                     List<Long> teamIds, long selfId);

    List<StatusCount> countByStatus(long tenantId, String scopeType, List<Long> teamIds, long selfId);

    record PolicyRow(long tenantId, boolean enabled, boolean emergencyStopped, int minIntervalSeconds,
                     int dailyLimit, java.time.LocalTime allowedStart, java.time.LocalTime allowedEnd,
                     String zoneId, String blockedTermsJson, long version) { }

    record StatusCount(String status, long count) { }
}
