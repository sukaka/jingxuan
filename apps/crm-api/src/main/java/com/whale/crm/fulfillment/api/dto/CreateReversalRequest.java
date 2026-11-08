package com.whale.crm.fulfillment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReversalRequest(@NotNull @Min(0) Long redemptionVersion,
                                    @NotBlank @Size(max = 1000) String reason) {
}
