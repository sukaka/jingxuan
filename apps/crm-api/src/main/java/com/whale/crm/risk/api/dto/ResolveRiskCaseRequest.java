package com.whale.crm.risk.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveRiskCaseRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 2000) String resolution) {
}
