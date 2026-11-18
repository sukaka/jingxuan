package com.whale.crm.customer.domain;

import java.time.Instant;

public record CustomerSegmentBatch(Long id, Long tenantId, Long segmentId, String batchNo, String operationType,
                                   String filterJson, Integer memberCount, Long createdBy, Instant createdAt) {
}
