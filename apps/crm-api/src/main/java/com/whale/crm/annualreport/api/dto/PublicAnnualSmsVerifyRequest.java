package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * C 端手机号验证码校验登录请求（验证后自动关联有赞/CRM 身份，并在微信态下永久绑定 OpenID）。
 */
public record PublicAnnualSmsVerifyRequest(
        @NotBlank @Pattern(regexp = "^\\+?\\d{5,15}$") String mobile,
        @NotBlank @Pattern(regexp = "^\\d{6}$") String code,
        @Min(2020) @Max(2099) Integer reportYear,
        @Size(max = 64) String shopId
) {
}
