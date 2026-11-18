package com.whale.crm.ai.domain;

import java.time.LocalTime;
import java.util.List;

public record AiChatDelegationPolicy(long tenantId, boolean enabled, boolean emergencyStopped,
                                     int minIntervalSeconds, int dailyLimit, LocalTime allowedStart,
                                     LocalTime allowedEnd, String zoneId, List<String> blockedTerms,
                                     long version, List<ScopeOverride> scopes) {
    public AiChatDelegationPolicy {
        blockedTerms = blockedTerms == null ? List.of() : List.copyOf(blockedTerms);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public static AiChatDelegationPolicy defaults(long tenantId) {
        return new AiChatDelegationPolicy(tenantId, false, false, 60, 10,
                LocalTime.of(9, 0), LocalTime.of(21, 0), "Asia/Shanghai", List.of(), 0, List.of());
    }

    public boolean scopeEnabled(String scopeType, Long scopeId) {
        if (scopeId == null) {
            return false;
        }
        return scopes.stream().anyMatch(scope -> scope.scopeType().equals(scopeType)
                && scope.scopeId() == scopeId && scope.enabled());
    }

    public record ScopeOverride(String scopeType, long scopeId, boolean enabled) { }
}
