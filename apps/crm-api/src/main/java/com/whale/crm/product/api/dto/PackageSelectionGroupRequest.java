package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PackageSelectionGroupRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,48}") String groupCode,
        @NotBlank @Size(max = 96) String groupName,
        @NotNull @Min(1) @Max(1000) Integer minSelections,
        @NotNull @Min(1) @Max(1000) Integer maxSelections,
        @NotNull @Min(0) @Max(10000) Integer sortOrder) {
}
