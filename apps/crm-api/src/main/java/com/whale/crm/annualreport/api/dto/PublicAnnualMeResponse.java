package com.whale.crm.annualreport.api.dto;

/**
 * C 端统一自动登录入口 `/me` 响应：直接返回登录态、是否需手机号兜底及本人年度报告快照。
 */
public record PublicAnnualMeResponse(
        boolean authenticated,
        boolean needPhoneBind,
        boolean hasPendingWechat,
        boolean wechatMpEnabled,
        boolean smsEnabled,
        boolean smsMockMode,
        boolean hasOrders,
        String authChannel,
        String dataSource,
        String shareKey,
        PublicAnnualReportResponse report
) {
}
