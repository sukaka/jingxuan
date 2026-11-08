package com.whale.crm.refund.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateRefundItemRequest(
        @NotNull Long entitlementId,
        @NotNull @Min(1) Integer revokeCount,
        @NotNull @Min(0) Long allocatedFen) {
}
