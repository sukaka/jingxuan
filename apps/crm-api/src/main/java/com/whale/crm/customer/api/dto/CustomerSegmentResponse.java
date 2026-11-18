package com.whale.crm.customer.api.dto;

import java.time.Instant;

public record CustomerSegmentResponse(long id, String name, CustomerSegmentCriteria filters,
                                      Instant createdAt, Instant updatedAt, long version) {
}
