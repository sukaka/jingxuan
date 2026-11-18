package com.whale.crm.followup.api.dto;

import java.time.Instant;
import java.util.List;

public record MonthlyBatchCandidateResponse(long customerId, String customerName, long ownerStaffId, Long teamId,
                                            Instant dueAt, List<Long> orderIds) {
    public MonthlyBatchCandidateResponse {
        orderIds = orderIds == null ? null : List.copyOf(orderIds);
    }
}
