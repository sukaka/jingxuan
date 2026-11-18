package com.whale.crm.integration.wecom.h5card.domain;

import java.time.Instant;

public record WecomH5Card(Long id, long tenantId, long projectId, String projectNameSnapshot, long customerId,
                          long employeeId, String externalUserid, String title, String summary, String content,
                          String shareKey, String status, String disableReason, Long disabledBy, Instant disabledAt,
                          Instant createdAt, Instant updatedAt, long version) {
}
