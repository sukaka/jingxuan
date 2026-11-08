package com.whale.crm.product.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreatePackageVersionRequest(
        @NotBlank @Pattern(regexp = "FIXED|CHOOSE_N|USE_N") String selectionMode,
        @NotNull @Min(0) Integer choiceCount,
        @Size(max = 4000) String selectionRules,
        @NotEmpty List<@Valid PackageProjectItemRequest> projects) {
    public CreatePackageVersionRequest {
        projects = projects == null ? null : List.copyOf(projects);
    }
}
