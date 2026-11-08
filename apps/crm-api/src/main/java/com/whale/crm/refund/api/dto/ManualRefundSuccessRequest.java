package com.whale.crm.refund.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ManualRefundSuccessRequest(
        @NotBlank @Size(max = 128) String externalRefundNo,
        @NotNull @Min(1) Long amountFen,
        @NotNull Instant succeededAt,
        @NotBlank @Size(max = 192) String evidenceRef,
        @NotNull Long voucherFileId) {
}
