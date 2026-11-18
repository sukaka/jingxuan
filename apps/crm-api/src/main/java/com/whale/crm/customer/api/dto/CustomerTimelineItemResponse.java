package com.whale.crm.customer.api.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 跨领域时间线摘要；details 只放白名单业务字段，不承载聊天全文。 */
public record CustomerTimelineItemResponse(
        String type,
        String eventKey,
        String sourceId,
        Instant occurredAt,
        String title,
        String summary,
        Map<String, Object> details,
        Long actorUserId) {
    public CustomerTimelineItemResponse {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    @Override
    public Map<String, Object> details() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static CustomerTimelineItemResponse of(String type, long sourceId, String discriminator,
                                                   Instant occurredAt, String title, String summary,
                                                   Map<String, Object> details, Long actorUserId) {
        String eventKey = String.format("%020d:%s", sourceId, discriminator);
        return new CustomerTimelineItemResponse(type, eventKey, Long.toString(sourceId), occurredAt,
                title, summary, details, actorUserId);
    }
}
