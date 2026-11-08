package com.whale.crm.customer.api.dto;

import java.time.Instant;

public record TaskItemResponse(
        Long id,
        Long customerId,
        Long assigneeUserId,
        Long originalAssigneeUserId,
        Long taskTeamId,
        String taskType,
        String priority,
        String status,
        String title,
        String description,
        Instant dueAt,
        Instant claimedAt,
        Instant completedAt,
        Instant createdAt,
        Long version) {
}
