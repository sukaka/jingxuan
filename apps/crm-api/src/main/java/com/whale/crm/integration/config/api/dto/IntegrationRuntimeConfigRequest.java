package com.whale.crm.integration.config.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record IntegrationRuntimeConfigRequest(
        @NotNull Boolean youzanRealEnabled,
        @NotNull Boolean wecomArchiveRealEnabled,
        @NotNull Boolean wecomOfficialEnabled,
        @NotNull @Min(0) Long version) {
}
