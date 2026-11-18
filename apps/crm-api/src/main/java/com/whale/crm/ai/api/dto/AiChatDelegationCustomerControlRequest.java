package com.whale.crm.ai.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiChatDelegationCustomerControlRequest(
        @NotBlank @Pattern(regexp = "ACTIVE|PAUSED|HUMAN_TAKEOVER") String mode,
        @NotNull @Min(0) Long version, @NotBlank @Size(max = 1000) String reason) {
}
