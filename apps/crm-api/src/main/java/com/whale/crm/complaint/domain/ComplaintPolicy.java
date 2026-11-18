package com.whale.crm.complaint.domain;

import java.time.Instant;

public record ComplaintPolicy(Long id, long tenantId, boolean enabled, Long fallbackRecipientUserId,
                              String empathyScript, Instant createdAt, Instant updatedAt, long version) {
}
