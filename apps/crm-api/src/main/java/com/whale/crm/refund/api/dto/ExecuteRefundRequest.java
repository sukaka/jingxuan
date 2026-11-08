package com.whale.crm.refund.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ExecuteRefundRequest(
        @NotNull Long version,
        @NotBlank @Pattern(regexp = "BANK_TRANSFER|OFFLINE|MOCK") String provider,
        @NotBlank @Size(max = 128) String externalRefundNo,
        @NotNull @Min(1) Long amountFen,
        @Size(max = 192) String evidenceRef) {
}
