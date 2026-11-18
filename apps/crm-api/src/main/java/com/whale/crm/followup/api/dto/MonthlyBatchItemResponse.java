package com.whale.crm.followup.api.dto;

import java.time.Instant;
import java.util.List;

public record MonthlyBatchItemResponse(long id, long customerId, String customerName, long ownerStaffId,
                                       Long teamId, Instant dueAt, Long taskId, String status, String reason,
                                       long version, List<Long> orderIds) {
    public MonthlyBatchItemResponse {
        orderIds = orderIds == null ? null : List.copyOf(orderIds);
    }
}
