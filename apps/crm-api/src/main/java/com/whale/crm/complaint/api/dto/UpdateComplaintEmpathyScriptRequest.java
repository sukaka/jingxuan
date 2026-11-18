package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateComplaintEmpathyScriptRequest(
        @NotBlank @Size(max = 64) String title,
        @NotBlank @Size(max = 2000) String script,
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
