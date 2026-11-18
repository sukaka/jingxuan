package com.whale.crm.customer.domain;

import java.time.Instant;

public record CustomerSegment(Long id, Long tenantId, String name, String filterJson, boolean deleted,
                              Long createdBy, Instant createdAt, Instant updatedAt, Long version) {
}
