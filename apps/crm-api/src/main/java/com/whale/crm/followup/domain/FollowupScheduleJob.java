package com.whale.crm.followup.domain;

public record FollowupScheduleJob(Long id, long tenantId, String jobType, String jobKey, String status,
                                  int retryCount, long version) {
}
