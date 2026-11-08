package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AppointmentItemRequest(@NotNull Long entitlementId, @NotNull @Min(1) Integer count) {
}
