package com.whale.crm.annualreport.api.dto;

/**
 * C 端统一入口能力探测响应（绝不含密钥或内部敏感配置）。
 */
public record PublicAnnualAuthConfigResponse(
        boolean wechatMpEnabled,
        boolean smsEnabled,
        boolean smsMockMode,
        int defaultYear,
        String wechatAuthorizePath
) {
}
