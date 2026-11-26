package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量生成年度购买轨迹 H5 链接请求（单批最多 200 位可见客户）。
 */
public record AnnualReportBatchGenerateRequest(
        @NotEmpty @Size(max = 200) List<@NotNull @Positive Long> customerIds,
        @NotNull @Min(2020) @Max(2099) Integer reportYear,
        @Pattern(regexp = "^(AUTO|POETIC|CLINICAL|MINIMAL)$") String toneCode,
        Boolean disablePreviousActive
) {
    public AnnualReportBatchGenerateRequest {
        customerIds = customerIds == null ? List.of() : List.copyOf(customerIds);
    }
}
