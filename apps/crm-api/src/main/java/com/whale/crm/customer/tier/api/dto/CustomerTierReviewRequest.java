package com.whale.crm.customer.tier.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CustomerTierReviewRequest(@NotNull Long version,
                                        @NotBlank @Size(max = 1000) String reason) {
}
