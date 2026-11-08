package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.databind.JsonNode;

public record YouzanMessage(String messageId, String eventType, String shopId, String orderNo,
                            long version, boolean test, JsonNode content) {
}
