package com.whale.crm.complaint.api.dto;

import com.whale.crm.complaint.domain.ComplaintPolicy;

import java.time.Instant;

public record ComplaintPolicyResponse(long id, boolean enabled, Long fallbackRecipientUserId, String empathyScript,
                                      Instant createdAt, Instant updatedAt, long version) {
    public static ComplaintPolicyResponse from(ComplaintPolicy policy) {
        return new ComplaintPolicyResponse(policy.id(), policy.enabled(), policy.fallbackRecipientUserId(),
                policy.empathyScript(), policy.createdAt(), policy.updatedAt(), policy.version());
    }
}
