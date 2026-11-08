package com.whale.crm.integration.youzan.adapter;

import java.util.List;
import java.util.Map;

public record YouzanPullPage(List<YouzanOrderEvent> events, String nextCursor, boolean hasMore,
                             Map<String, String> rawPayloads) {
    public YouzanPullPage {
        events = events == null ? null : List.copyOf(events);
        rawPayloads = rawPayloads == null ? null : Map.copyOf(rawPayloads);
    }

    public YouzanPullPage(List<YouzanOrderEvent> events, String nextCursor, boolean hasMore) {
        this(events, nextCursor, hasMore, Map.of());
    }
}
