package com.whale.crm.quickreply.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record QuickReplyUsageRequest(
        @NotNull @Positive Long customerId,
        @NotBlank @Size(max = 192) String externalUserid,
        @NotBlank @Size(max = 64) String scene,
        @NotBlank @Size(max = 2000) String originalContent,
        @NotBlank @Size(max = 4000) String finalContent,
        boolean edited,
        @NotBlank @Pattern(regexp = "COPIED|SDK_SUCCESS|SDK_FAILED|SDK_UNKNOWN") String sendStatus,
        @Size(max = 512) String clientResult,
        @Positive Long aiSuggestionId) {
}
