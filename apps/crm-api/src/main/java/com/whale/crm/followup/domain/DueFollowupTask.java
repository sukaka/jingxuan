package com.whale.crm.followup.domain;

import java.time.Instant;

public record DueFollowupTask(long id, long tenantId, long customerId, long assigneeUserId, Instant dueAt) {
}
