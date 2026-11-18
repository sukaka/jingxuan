package com.whale.crm.quickreply.application;

import com.whale.crm.platform.security.CurrentUser;

import java.util.Map;

public record QuickReplyContext(long tenantId, CurrentUser user, long customerId, String scene,
                                Map<String, Object> facts) {
    public QuickReplyContext {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
