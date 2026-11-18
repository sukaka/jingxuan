package com.whale.crm.customer.tier.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CustomerTierAdjustmentRequest(
        @NotBlank @Pattern(regexp = "S|A|B|C|D") String tierCode,
        @NotBlank @Size(max = 1000) String reason,
        @NotNull Boolean lock,
        @NotNull Long version) {
}
