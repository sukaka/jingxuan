package com.whale.crm.ai.api.dto;

import com.whale.crm.ai.domain.AiChatDelegationCustomerControl;

import java.time.Instant;

public record AiChatDelegationCustomerControlResponse(long customerId, String mode, String reason,
                                                      Instant updatedAt, long version) {
    public static AiChatDelegationCustomerControlResponse of(AiChatDelegationCustomerControl control) {
        return new AiChatDelegationCustomerControlResponse(control.customerId(), control.mode(), control.reason(),
                control.updatedAt(), control.version());
    }
}
