package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewReversalRequest(@NotNull @Min(0) Long version, @Size(max = 1000) String reason) {
}
