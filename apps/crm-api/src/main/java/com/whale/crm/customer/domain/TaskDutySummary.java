package com.whale.crm.customer.domain;

public record TaskDutySummary(long pendingTaskCount, long dueFollowupCount, long overdueTaskCount) {
    public static TaskDutySummary empty() {
        return new TaskDutySummary(0, 0, 0);
    }
}
