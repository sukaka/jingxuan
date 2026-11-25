package com.whale.crm.complaint.domain;

import java.time.Instant;
import java.util.List;

public record ComplaintTimelineQuery(long tenantId, String scopeType, List<Long> teamIds, long selfId,
                                     long customerId, String cursorMode, Instant cursorOccurredAt,
                                     Long cursorSourceId, int limit) {
    public ComplaintTimelineQuery {
        teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
    }
}
