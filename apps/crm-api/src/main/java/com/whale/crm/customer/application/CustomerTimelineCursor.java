package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;

import java.time.Instant;
import java.util.Comparator;

public record CustomerTimelineCursor(Instant occurredAt, String type, String eventKey) {
    public static final Comparator<CustomerTimelineItemResponse> ORDER = Comparator
            .comparing(CustomerTimelineItemResponse::occurredAt, Comparator.reverseOrder())
            .thenComparing(CustomerTimelineItemResponse::type, Comparator.reverseOrder())
            .thenComparing(CustomerTimelineItemResponse::eventKey, Comparator.reverseOrder());

    public enum CursorMode {
        ALL,
        LE,
        EQ,
        LT
    }

    public record SqlCursor(CursorMode mode, Instant occurredAt, Long sourceId) {
        public String modeName() {
            return mode.name();
        }
    }

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

    public Long sourceId() {
        if (eventKey == null || eventKey.length() < 21 || eventKey.charAt(20) != ':') {
            return null;
        }
        for (int i = 0; i < 20; i++) {
            char ch = eventKey.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(eventKey.substring(0, 20));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static SqlCursor sqlCursor(CustomerTimelineCursor cursor, String tableType) {
        if (cursor == null) {
            return new SqlCursor(CursorMode.ALL, null, null);
        }
        int cmp = tableType.compareTo(cursor.type());
        if (cmp < 0) {
            return new SqlCursor(CursorMode.LE, cursor.occurredAt(), null);
        }
        if (cmp == 0) {
            Long sid = cursor.sourceId();
            return sid != null
                    ? new SqlCursor(CursorMode.EQ, cursor.occurredAt(), sid)
                    : new SqlCursor(CursorMode.LE, cursor.occurredAt(), null);
        }
        return new SqlCursor(CursorMode.LT, cursor.occurredAt(), null);
    }
}
