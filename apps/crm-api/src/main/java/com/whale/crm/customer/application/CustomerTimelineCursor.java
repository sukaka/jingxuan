package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;

import java.time.Instant;
import java.util.Comparator;

public record CustomerTimelineCursor(Instant occurredAt, String type, String eventKey) {
    public static final Comparator<CustomerTimelineItemResponse> ORDER = Comparator
            .comparing(CustomerTimelineItemResponse::occurredAt, Comparator.reverseOrder())
            .thenComparing(CustomerTimelineItemResponse::type, Comparator.reverseOrder())
            .thenComparing(CustomerTimelineItemResponse::eventKey, Comparator.reverseOrder());

    public boolean allows(CustomerTimelineItemResponse item) {
        int time = item.occurredAt().compareTo(occurredAt);
        if (time != 0) {
            return time < 0;
        }
        int typeOrder = item.type().compareTo(type);
        if (typeOrder != 0) {
            return typeOrder < 0;
        }
        return item.eventKey().compareTo(eventKey) < 0;
    }
}
