package com.whale.crm.annualreport.api.dto;

/**
 * C 端手机号验证码登录回执。
 */
public record PublicAnnualSmsVerifyResponse(
        boolean authenticated,
        boolean matchedProfile,
        boolean wechatBound,
        String displayName,
        String message
) {
}
