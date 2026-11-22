package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateChannelFinancialChangeRequest(
        @Digits(integer = 3, fraction = 2) @DecimalMin("0") @DecimalMax("100") BigDecimal commissionPercent,
        @Size(max = 64) String settlementMethod,
        @Size(max = 160) String paymentAccountName,
        @Size(max = 64) String paymentAccountNo,
        @Size(max = 255) String paymentBankName,
        @NotNull @Min(0) Long baseVersion,
        @NotBlank @Size(max = 1000) String reason) {
}
