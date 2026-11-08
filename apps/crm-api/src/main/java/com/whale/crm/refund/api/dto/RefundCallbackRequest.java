package com.whale.crm.refund.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record RefundCallbackRequest(
        @NotBlank @Size(max = 128) String externalRefundNo,
        @NotNull @Min(1) Long amountFen,
        @NotBlank @Pattern(regexp = "SUCCEEDED|FAILED") String status,
        @NotNull Instant occurredAt,
        @Size(max = 192) String evidenceRef) {
}
