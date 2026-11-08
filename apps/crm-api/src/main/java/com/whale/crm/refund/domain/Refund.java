package com.whale.crm.refund.domain;

import java.time.Instant;

public record Refund(Long id, Long tenantId, String refundNo, Long orderId, Long customerId,
                     Long requestedFen, Long approvedFen, Long succeededFen, String reasonCode,
                     String reasonDetail, String status, Long requestedBy, Long approvedBy,
                     Long executedBy, Instant succeededAt, Instant createdAt, Instant updatedAt, Long version) { }
