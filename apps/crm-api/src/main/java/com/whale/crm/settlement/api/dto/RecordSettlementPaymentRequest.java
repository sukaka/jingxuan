package com.whale.crm.settlement.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record RecordSettlementPaymentRequest(
        @NotNull @PositiveOrZero Long version,
        @NotNull @Positive Long amountFen,
        @NotBlank @Size(max = 128) String bankTradeNo,
        @NotNull Instant paidAt,
        @NotNull @Positive Long voucherFileId,
        @NotBlank @Size(max = 192) String evidenceRef) {
}
