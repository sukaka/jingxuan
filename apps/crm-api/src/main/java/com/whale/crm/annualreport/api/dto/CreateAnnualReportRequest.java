package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 为指定 CRM 客户生成年度购买轨迹 H5 快照与公开链接的请求。
 */
public record CreateAnnualReportRequest(
        @NotNull @Positive Long customerId,
        @NotNull @Min(2020) @Max(2099) Integer reportYear,
        @Pattern(regexp = "^(AUTO|POETIC|CLINICAL|MINIMAL)$") String toneCode,
        @Pattern(regexp = "^(FEMALE|MALE|UNKNOWN)$") String genderHint,
        @Size(max = 200) String customButlerNote,
        Boolean disablePreviousActive
) {
}
