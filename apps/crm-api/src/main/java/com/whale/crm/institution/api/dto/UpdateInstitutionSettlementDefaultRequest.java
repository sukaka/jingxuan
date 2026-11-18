package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UpdateInstitutionSettlementDefaultRequest(
        @NotBlank @Size(max = 16) String defaultSettlementDay,
        @NotNull @Min(0) Long version) {
}
