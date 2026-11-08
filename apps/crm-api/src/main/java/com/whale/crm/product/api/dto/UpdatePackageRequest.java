package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdatePackageRequest(
        @NotBlank @Size(max = 160) String packageName,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @NotNull @Min(0) Long version) {
}
