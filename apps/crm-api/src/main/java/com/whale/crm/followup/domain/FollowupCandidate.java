package com.whale.crm.followup.domain;

import java.time.Instant;
import java.util.List;

public record FollowupCandidate(long customerId, String customerName, long ownerStaffId, Long teamId,
                                Instant dueAt, List<Long> orderIds) {
    public FollowupCandidate {
        orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
    }
}
