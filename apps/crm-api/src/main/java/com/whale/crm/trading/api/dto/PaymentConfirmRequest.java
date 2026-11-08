package com.whale.crm.trading.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record PaymentConfirmRequest(
        @NotBlank @Size(max = 128) String externalTradeNo,
        @NotBlank @Pattern(regexp = "BANK_TRANSFER|OFFLINE") String paymentChannel,
        @NotNull @Min(1) Long amountFen,
        @NotNull Instant paidAt,
        @NotBlank @Size(max = 192) String evidenceRef,
        Long voucherFileId) {
}
