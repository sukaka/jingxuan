package com.whale.crm.product.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceVersionProjectsRequest(
        @NotNull @Min(0) Long version,
        @NotEmpty List<@Valid PackageProjectItemRequest> projects,
        @Valid List<PackageSelectionGroupRequest> groups) {
    public ReplaceVersionProjectsRequest {
        projects = projects == null ? null : List.copyOf(projects);
        groups = groups == null ? null : List.copyOf(groups);
    }
}
