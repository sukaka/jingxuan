package com.whale.crm.integration.youzan.adapter;

import java.time.Instant;

public record YouzanSyncCursorValue(String queryType, Instant windowStart, Instant windowEnd,
                                    Instant finalEnd, int pageNo) {
    public static YouzanSyncCursorValue created(Instant start, Instant end) {
        return new YouzanSyncCursorValue("CREATED", start, end, end, 1);
    }

    public static YouzanSyncCursorValue updated(Instant start, Instant end) {
        return new YouzanSyncCursorValue("UPDATED", start, end, end, 1);
    }
}
