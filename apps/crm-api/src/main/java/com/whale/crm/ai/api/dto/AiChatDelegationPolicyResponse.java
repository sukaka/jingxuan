package com.whale.crm.ai.api.dto;

import com.whale.crm.ai.domain.AiChatDelegationPolicy;

import java.util.List;

public record AiChatDelegationPolicyResponse(long tenantId, boolean enabled, boolean emergencyStopped,
                                             int minIntervalSeconds, int dailyLimit, String allowedStart,
                                             String allowedEnd, String zoneId, List<String> blockedTerms,
                                             List<ScopeResponse> scopes, long version) {
    public AiChatDelegationPolicyResponse {
        blockedTerms = List.copyOf(blockedTerms);
        scopes = List.copyOf(scopes);
    }

    public static AiChatDelegationPolicyResponse of(AiChatDelegationPolicy policy) {
        return new AiChatDelegationPolicyResponse(policy.tenantId(), policy.enabled(), policy.emergencyStopped(),
                policy.minIntervalSeconds(), policy.dailyLimit(), policy.allowedStart().toString().substring(0, 5),
                policy.allowedEnd().toString().substring(0, 5), policy.zoneId(), policy.blockedTerms(),
                policy.scopes().stream().map(scope -> new ScopeResponse(scope.scopeType(), scope.scopeId(),
                        scope.enabled())).toList(), policy.version());
    }

    public record ScopeResponse(String scopeType, long scopeId, boolean enabled) { }
}
