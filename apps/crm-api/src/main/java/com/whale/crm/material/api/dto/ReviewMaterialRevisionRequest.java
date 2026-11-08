package com.whale.crm.material.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewMaterialRevisionRequest(
        @NotNull @Min(0) Long version,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @Size(max = 1000) String reviewNote) {
}
