package com.whale.crm.settlement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record VerifySettlementBatchRequest(
        @NotNull @PositiveOrZero Long version,
        @NotBlank @Size(max = 96) String ruleVersion,
        @NotNull @PositiveOrZero Long grossFen,
        @NotNull @PositiveOrZero Long commissionFen,
        @NotNull @PositiveOrZero Long netPayableFen,
        @NotBlank @Size(max = 192) String verificationEvidence) {
}
