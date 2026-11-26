package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * C 端手机号验证码发送请求（微信外或 OpenID 未命中老客兜底）。
 */
public record PublicAnnualSmsSendRequest(
        @NotBlank @Pattern(regexp = "^\\+?\\d{5,15}$") String mobile
) {
}
