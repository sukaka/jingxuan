package com.whale.crm.institution.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateContractRequest(@NotNull Long institutionId,
                                    Long previousContractId,
                                    @NotBlank @Size(max = 96) String contractNo,
                                    @NotBlank @Size(max = 192) String title,
                                    @NotBlank @Size(max = 64) String contractType,
                                    @NotNull LocalDate startDate,
                                    LocalDate endDate,
                                    @NotNull Long fileId,
                                    @Min(0) Long amountFen,
                                    @Size(max = 1000) String notes) {
}
