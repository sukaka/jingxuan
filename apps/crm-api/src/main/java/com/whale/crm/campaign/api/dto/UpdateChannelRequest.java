package com.whale.crm.campaign.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateChannelRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{2,48}") String channelNo,
        @NotBlank @Size(max = 160) String name,
        @Pattern(regexp = "BLOGGER|DISTRIBUTION|REFERRAL") String channelType,
        @Size(max = 64) String platformCode,
        @Digits(integer = 3, fraction = 2) @DecimalMin("0") @DecimalMax("100") BigDecimal commissionPercent,
        Long businessOwnerUserId,
        @Size(max = 80) String realName,
        @Size(max = 32) String contactPhone,
        @Size(max = 18) String idCardNumber,
        @Size(max = 64) String settlementMethod,
        LocalDate cooperationStartDate,
        LocalDate cooperationEndDate,
        @Size(max = 160) String paymentAccountName,
        @Size(max = 64) String paymentAccountNo,
        @Size(max = 255) String paymentBankName,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|ARCHIVED") String status,
        @Size(max = 4000) String remarks,
        @NotNull @Min(0) Long version) {
}
