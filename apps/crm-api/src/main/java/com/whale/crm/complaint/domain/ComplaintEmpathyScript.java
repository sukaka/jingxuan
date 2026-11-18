package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintEmpathyScript(Long id, long tenantId, String title, String script, String status,
                                     Long createdBy, Instant createdAt, Instant updatedAt, long version) {
}
