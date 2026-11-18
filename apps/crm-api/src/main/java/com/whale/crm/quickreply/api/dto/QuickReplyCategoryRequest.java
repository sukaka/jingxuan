package com.whale.crm.quickreply.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record QuickReplyCategoryRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 48) String code,
        @Positive Long parentId,
        @NotBlank @Pattern(regexp = "GLOBAL|INSTITUTION|DEPARTMENT|PERSONAL") String scopeType,
        @Positive Long scopeId,
        @PositiveOrZero int sortNo,
        @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
