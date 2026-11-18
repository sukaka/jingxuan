package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintKeyword(Long id, long tenantId, String keyword, String status, Long createdBy,
                               Instant createdAt, Instant updatedAt, long version) {
}
