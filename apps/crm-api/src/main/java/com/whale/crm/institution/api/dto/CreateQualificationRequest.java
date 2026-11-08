package com.whale.crm.institution.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateQualificationRequest(@NotNull Long institutionId,
                                         @NotBlank @Size(max = 64) String qualificationType,
                                         @NotBlank @Size(max = 192) String title,
                                         @Size(max = 96) String licenseNo,
                                         @NotNull Long fileId,
                                         LocalDate validFrom,
                                         LocalDate validTo) {
}
