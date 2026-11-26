package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 停用年度 H5 公开链接请求（乐观锁 version 校验）。
 */
public record AnnualReportDisableRequest(
        @NotNull @Min(0) Long version,
        @NotBlank @Size(max = 512) String reason
) {
}
