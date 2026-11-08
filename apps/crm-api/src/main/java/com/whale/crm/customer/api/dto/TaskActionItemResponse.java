package com.whale.crm.customer.api.dto;

import java.time.Instant;

public record TaskActionItemResponse(
        Long id,
        Long taskId,
        String action,
        String fromStatus,
        String toStatus,
        Long fromAssigneeUserId,
        Long toAssigneeUserId,
        Long actorUserId,
        String reason,
        Instant createdAt) {
}
