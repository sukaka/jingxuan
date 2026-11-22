package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ToggleChannelStatusRequest(
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @NotNull @Min(0) Long version,
        @Size(max = 500) String reason) {
}
