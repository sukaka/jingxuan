package com.whale.crm.quickreply.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record QuickReplyTemplateRequest(
        @NotNull @Positive Long categoryId,
        @NotBlank @Size(max = 96) String name,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Pattern(regexp = "TEXT") String messageType,
        @NotBlank @Size(max = 2000) String content,
        @NotBlank @Size(max = 64) String scene,
        @NotBlank @Pattern(regexp = "GLOBAL|INSTITUTION|DEPARTMENT|PERSONAL") String scopeType,
        @Positive Long scopeId,
        boolean allowEdit,
        boolean requirePreview,
        boolean aiEnabled,
        boolean featured,
        @PositiveOrZero int sortNo,
        @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
