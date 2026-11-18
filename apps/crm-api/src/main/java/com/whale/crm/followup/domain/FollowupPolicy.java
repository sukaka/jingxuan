package com.whale.crm.followup.domain;

import java.util.List;

public record FollowupPolicy(long tenantId, int defaultIntervalDays, long version,
                             List<IntentOverride> intentOverrides) {
    public FollowupPolicy {
        intentOverrides = intentOverrides == null ? List.of() : List.copyOf(intentOverrides);
    }

    public static FollowupPolicy defaults(long tenantId) {
        return new FollowupPolicy(tenantId, 14, 0, List.of());
    }

    public int intervalFor(String intentLevel) {
        if (intentLevel != null) {
            for (IntentOverride override : intentOverrides) {
                if (override.intentLevel().equalsIgnoreCase(intentLevel.strip())) {
                    return override.intervalDays();
                }
            }
        }
        return defaultIntervalDays;
    }

    public record IntentOverride(String intentLevel, int intervalDays) {
    }
}
