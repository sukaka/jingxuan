package com.whale.crm.integration.wecom.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WecomLinkRequest(@NotNull Long customerId, @NotNull Long version,
                               @NotBlank @Size(max = 1000) String reason) {
}
