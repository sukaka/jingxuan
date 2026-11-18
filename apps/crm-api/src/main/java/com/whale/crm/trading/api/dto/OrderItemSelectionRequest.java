package com.whale.crm.trading.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record OrderItemSelectionRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,48}") String groupCode,
        @NotNull Long projectId) {
}
