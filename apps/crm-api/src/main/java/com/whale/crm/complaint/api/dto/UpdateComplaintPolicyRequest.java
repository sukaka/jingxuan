package com.whale.crm.complaint.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateComplaintPolicyRequest(
        @NotNull Boolean enabled,
        @Positive Long fallbackRecipientUserId,
        @NotBlank @Size(max = 2000) String empathyScript,
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 1000) String reason) {
}
