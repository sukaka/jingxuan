package com.whale.crm.material.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateMaterialRequest(
        @NotNull @Positive Long revisionId,
        @NotNull @Min(0) Long version,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 48) String categoryCode,
        @Positive Long institutionId,
        @Positive Long projectId,
        @Size(max = 64) String cityCode,
        @Size(max = 2000) String summary,
        @Size(max = 20) List<@NotBlank @Size(max = 40) String> tags,
        @NotNull @Positive Long fileId,
        @Size(max = 1000) String changeNote) {
    public UpdateMaterialRequest {
        tags = tags == null ? null : List.copyOf(tags);
    }
}
