package com.whale.crm.customer.api.dto;

import java.time.Instant;

public record CustomerSegmentBatchResponse(long id, long segmentId, String batchNo, String operationType,
                                           int memberCount, Instant createdAt) {
}
