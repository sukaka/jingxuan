package com.whale.crm.followup.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateFollowupPolicyRequest(
        @NotNull @Min(1) @Max(365) Integer defaultIntervalDays,
        @NotNull @Size(max = 32) List<@Valid IntentOverrideRequest> intentOverrides,
        @NotNull @PositiveOrZero Long version) {
    public UpdateFollowupPolicyRequest {
        intentOverrides = intentOverrides == null ? null : List.copyOf(intentOverrides);
    }

    public record IntentOverrideRequest(
            @NotBlank @Size(max = 32) String intentLevel,
            @NotNull @Min(1) @Max(365) Integer intervalDays) {
    }
}
