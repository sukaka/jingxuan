package com.whale.crm.ai.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateAiChatDelegationPolicyRequest(
        @NotNull Boolean enabled,
        @NotNull @Min(0) @Max(3600) Integer minIntervalSeconds,
        @NotNull @Min(1) @Max(100) Integer dailyLimit,
        @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String allowedStart,
        @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String allowedEnd,
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 64) String> blockedTerms,
        @NotNull @Size(max = 500) List<@Valid ScopeRequest> scopes,
        @NotNull @Min(0) Long version,
        @NotBlank @Size(max = 1000) String reason) {
    public UpdateAiChatDelegationPolicyRequest {
        blockedTerms = List.copyOf(blockedTerms);
        scopes = List.copyOf(scopes);
    }

    public record ScopeRequest(@NotBlank @Pattern(regexp = "TEAM|STAFF") String scopeType,
                               @NotNull @Positive Long scopeId, @NotNull Boolean enabled) { }
}
