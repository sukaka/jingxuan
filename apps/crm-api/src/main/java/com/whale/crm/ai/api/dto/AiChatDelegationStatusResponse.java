package com.whale.crm.ai.api.dto;

import java.util.Map;

public record AiChatDelegationStatusResponse(boolean enabled, boolean emergencyStopped, boolean workerEnabled,
                                             boolean eventConsumerEnabled, String senderMode,
                                             boolean realOfficialEnabled, Map<String, Long> counts) {
    public AiChatDelegationStatusResponse {
        counts = counts == null ? Map.of() : Map.copyOf(counts);
    }
}
