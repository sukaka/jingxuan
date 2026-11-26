package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * C 端客户在年终 H5 第 5 幕点击「通知专属管家预约」的预约意向请求。
 */
public record PublicAnnualBookingRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$") String sessionKey,
        @Size(max = 200) String note
) {
}
