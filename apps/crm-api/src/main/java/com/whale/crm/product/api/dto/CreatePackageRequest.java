package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePackageRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,64}") String packageCode,
        @NotBlank @Size(max = 160) String packageName) {
}
