package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.AiChatDelegationAttempt;
import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;
import com.whale.crm.ai.domain.AiChatDelegationPolicy;
import com.whale.crm.ai.domain.AiChatDelegationRepository;
import com.whale.crm.ai.domain.AiChatDelegationRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AiChatDelegationMapper {
    AiChatDelegationRepository.PolicyRow selectPolicy(@Param("tenantId") long tenantId);

    AiChatDelegationRepository.PolicyRow selectPolicyForUpdate(@Param("tenantId") long tenantId);

    List<AiChatDelegationPolicy.ScopeOverride> selectScopes(@Param("tenantId") long tenantId);

    void insertPolicy(@Param("e") AiChatDelegationPolicy policy, @Param("blockedTermsJson") String blockedTermsJson,
                      @Param("actorUserId") long actorUserId);

    int updatePolicy(@Param("e") AiChatDelegationPolicy policy, @Param("blockedTermsJson") String blockedTermsJson,
                     @Param("actorUserId") long actorUserId, @Param("expectedVersion") long expectedVersion);

    void deleteScopes(@Param("tenantId") long tenantId);

    void insertScope(@Param("tenantId") long tenantId, @Param("scope") AiChatDelegationPolicy.ScopeOverride scope);

    AiChatDelegationCustomerControl selectCustomerControl(@Param("tenantId") long tenantId,
                                                           @Param("customerId") long customerId);

    void insertCustomerControl(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                               @Param("mode") String mode, @Param("reason") String reason,
                               @Param("actorUserId") long actorUserId);

    int updateCustomerControl(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                              @Param("mode") String mode, @Param("reason") String reason,
                              @Param("actorUserId") long actorUserId, @Param("expectedVersion") long expectedVersion);

    List<AiChatDelegationRun> selectPendingForCustomerForUpdate(@Param("tenantId") long tenantId,
                                                                 @Param("customerId") long customerId);

    int blockPendingForCustomer(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                                @Param("reason") String reason, @Param("now") Instant now);

    void insertRun(@Param("e") AiChatDelegationRun run);

    AiChatDelegationRun selectRunByMessage(@Param("tenantId") long tenantId,
                                           @Param("sourceMessageId") long sourceMessageId);

    AiChatDelegationRun selectRun(@Param("tenantId") long tenantId, @Param("id") long id);

    AiChatDelegationRun selectRunForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);

    List<Long> selectWorkTenantIds(@Param("now") Instant now);

    List<AiChatDelegationRun> selectClaimableGeneration(@Param("tenantId") long tenantId,
                                                         @Param("now") Instant now, @Param("limit") int limit);

    List<AiChatDelegationRun> selectClaimableSending(@Param("tenantId") long tenantId,
                                                      @Param("now") Instant now, @Param("limit") int limit);

    List<AiChatDelegationRun> selectExpiredSending(@Param("now") Instant now, @Param("limit") int limit);

    int claimGeneration(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                        @Param("now") Instant now, @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int claimSending(@Param("tenantId") long tenantId, @Param("id") long id,
                     @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                     @Param("now") Instant now, @Param("leaseExpiresAt") Instant leaseExpiresAt);

    int markGenerated(@Param("tenantId") long tenantId, @Param("id") long id,
                      @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                      @Param("replyCipher") String replyCipher, @Param("intent") String intent,
                      @Param("riskFlagsJson") String riskFlagsJson, @Param("aiProvider") String aiProvider,
                      @Param("aiModel") String aiModel, @Param("now") Instant now);

    int updateRunStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
                        @Param("targetStatus") String targetStatus, @Param("reasonMasked") String reasonMasked,
                        @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

    int markSendAttempted(@Param("tenantId") long tenantId, @Param("id") long id,
                          @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                          @Param("now") Instant now);

    int finishSend(@Param("tenantId") long tenantId, @Param("id") long id,
                   @Param("expectedVersion") long expectedVersion, @Param("owner") String owner,
                   @Param("status") String status, @Param("reasonMasked") String reasonMasked,
                   @Param("provider") String provider, @Param("providerMessageId") String providerMessageId,
                   @Param("nextAttemptAt") Instant nextAttemptAt, @Param("now") Instant now);

    Instant selectLatestSendAttempt(@Param("tenantId") long tenantId, @Param("customerId") long customerId);

    long countSendAttemptsSince(@Param("tenantId") long tenantId, @Param("customerId") long customerId,
                                @Param("since") Instant since);

    void insertAttempt(@Param("e") AiChatDelegationAttempt attempt);

    List<AiChatDelegationRun> search(@Param("tenantId") long tenantId, @Param("customerId") Long customerId,
                                     @Param("status") String status, @Param("scopeType") String scopeType,
                                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId,
                                     @Param("offset") int offset, @Param("size") int size);

    long countSearch(@Param("tenantId") long tenantId, @Param("customerId") Long customerId,
                     @Param("status") String status, @Param("scopeType") String scopeType,
                     @Param("teamIds") List<Long> teamIds, @Param("selfId") long selfId);

    List<AiChatDelegationRepository.StatusCount> countByStatus(@Param("tenantId") long tenantId,
                                                                @Param("scopeType") String scopeType,
                                                                @Param("teamIds") List<Long> teamIds,
                                                                @Param("selfId") long selfId);
}
