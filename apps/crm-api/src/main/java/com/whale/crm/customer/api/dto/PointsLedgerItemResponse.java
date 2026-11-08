package com.whale.crm.customer.api.dto;

import java.time.Instant;

public record PointsLedgerItemResponse(
        Long id,
        Integer delta,
        String reasonCode,
        String sourceType,
        String sourceRef,
        Long operatorUserId,
        Instant createdAt) {
}
