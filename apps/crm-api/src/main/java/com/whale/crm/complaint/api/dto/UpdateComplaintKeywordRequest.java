package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateComplaintKeywordRequest(
        @NotBlank @Size(max = 128) String keyword,
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
