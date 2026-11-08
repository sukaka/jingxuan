package com.whale.crm.institution.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateInstitutionMerchantResourceRequest(
        @NotBlank @Pattern(regexp = "DOCTOR|EQUIPMENT|CASE") String resourceType,
        @NotBlank @Size(max = 120) String title,
        @Size(max = 200) String subtitle,
        @Size(max = 2000) String description,
        @Positive Long fileId) {
}
