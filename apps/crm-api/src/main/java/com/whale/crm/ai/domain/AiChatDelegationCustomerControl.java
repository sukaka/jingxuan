package com.whale.crm.ai.domain;

import java.time.Instant;

public record AiChatDelegationCustomerControl(Long id, long tenantId, long customerId, String mode,
                                              String reason, Long updatedBy, Instant updatedAt, long version) {
    public static AiChatDelegationCustomerControl active(long tenantId, long customerId) {
        return new AiChatDelegationCustomerControl(null, tenantId, customerId, "ACTIVE", null, null, null, 0);
    }
}
