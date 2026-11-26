package com.whale.crm.annualreport.api.dto;

/**
 * C 端手机号验证码发送回执（仅本地 MOCK 模式带 devPreviewCode，生产为 null）。
 */
public record PublicAnnualSmsSendResponse(
        boolean sent,
        int ttlSeconds,
        int cooldownSeconds,
        String mobileMasked,
        String devPreviewCode
) {
}
