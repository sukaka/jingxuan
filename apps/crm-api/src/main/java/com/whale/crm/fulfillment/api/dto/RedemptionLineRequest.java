package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record RedemptionLineRequest(@NotNull Long entitlementId, @NotNull @Min(1) Integer count) {
}
