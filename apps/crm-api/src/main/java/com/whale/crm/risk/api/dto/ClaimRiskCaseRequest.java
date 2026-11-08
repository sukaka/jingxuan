package com.whale.crm.risk.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ClaimRiskCaseRequest(
        @NotNull Long version,
        @Size(max = 1000) String reason) {
}
