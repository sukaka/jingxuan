package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String projectCode,
        @NotBlank @Size(max = 160) String projectName,
        @Size(max = 64) String categoryCode,
        @NotNull @Min(0) Long listPriceFen) {
}
