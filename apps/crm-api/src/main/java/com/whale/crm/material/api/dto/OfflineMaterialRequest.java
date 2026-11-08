package com.whale.crm.material.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OfflineMaterialRequest(
        @NotNull @Min(0) Long version,
        @NotBlank @Size(max = 500) String reason) {
}
