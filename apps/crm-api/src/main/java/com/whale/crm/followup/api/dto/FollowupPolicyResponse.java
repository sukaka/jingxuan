package com.whale.crm.followup.api.dto;

import java.util.List;

public record FollowupPolicyResponse(long tenantId, int defaultIntervalDays, long version,
                                     List<IntentOverrideResponse> intentOverrides) {
    public FollowupPolicyResponse {
        intentOverrides = intentOverrides == null ? null : List.copyOf(intentOverrides);
    }

    public record IntentOverrideResponse(String intentLevel, int intervalDays) {
    }
}
