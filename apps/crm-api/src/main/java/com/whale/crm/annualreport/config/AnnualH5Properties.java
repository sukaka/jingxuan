package com.whale.crm.annualreport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 年终购买轨迹 H5 统一自动登录配置（微信服务号静默授权 + 小程序换票 + 手机号验证码兜底）。
 * 所有密钥均通过环境变量注入，默认全部关闭，禁止在代码或仓库中硬编码真实凭证。
 */
@ConfigurationProperties(prefix = "app.annual-h5")
public record AnnualH5Properties(
        long defaultTenantId,
        String publicOrigin,
        String sessionCookieName,
        String pendingCookieName,
        String stateCookieName,
        long sessionTtlSeconds,
        WechatMp wechatMp,
        MiniProgram miniprogram,
        Sms sms
) {
    public AnnualH5Properties {
        defaultTenantId = defaultTenantId <= 0 ? 1L : defaultTenantId;
        publicOrigin = (publicOrigin == null || publicOrigin.isBlank())
                ? "http://localhost:5173" : publicOrigin.replaceAll("/+$", "");
        sessionCookieName = (sessionCookieName == null || sessionCookieName.isBlank())
                ? "WHALE_H5_SESSION" : sessionCookieName;
        pendingCookieName = (pendingCookieName == null || pendingCookieName.isBlank())
                ? "WHALE_H5_WECHAT_PENDING" : pendingCookieName;
        stateCookieName = (stateCookieName == null || stateCookieName.isBlank())
                ? "WHALE_H5_OAUTH_STATE" : stateCookieName;
        sessionTtlSeconds = sessionTtlSeconds <= 0 ? 604800L : sessionTtlSeconds;
        wechatMp = wechatMp == null ? new WechatMp(false, "", "",
                "https://open.weixin.qq.com/connect/oauth2/authorize",
                "https://api.weixin.qq.com", "") : wechatMp;
        miniprogram = miniprogram == null ? new MiniProgram(false, "", "", "https://api.weixin.qq.com") : miniprogram;
        sms = sms == null ? new Sms("DISABLED", 300, 60, "", "") : sms;
    }

    public boolean isWechatMpConfigured() {
        return wechatMp.enabled()
                && wechatMp.appId() != null && !wechatMp.appId().isBlank()
                && wechatMp.appSecret() != null && !wechatMp.appSecret().isBlank()
                && wechatMp.callbackUrl() != null && !wechatMp.callbackUrl().isBlank();
    }

    public boolean isMiniprogramConfigured() {
        return miniprogram.enabled()
                && miniprogram.appId() != null && !miniprogram.appId().isBlank()
                && miniprogram.appSecret() != null && !miniprogram.appSecret().isBlank();
    }

    public boolean isSmsEnabled() {
        String mode = sms.mode() == null ? "DISABLED" : sms.mode().trim().toUpperCase(java.util.Locale.ROOT);
        return "MOCK".equals(mode) || ("WEBHOOK".equals(mode) && sms.webhookUrl() != null && !sms.webhookUrl().isBlank());
    }

    public boolean isSmsMockMode() {
        return sms.mode() != null && "MOCK".equalsIgnoreCase(sms.mode().trim());
    }

    public record WechatMp(
            boolean enabled,
            String appId,
            String appSecret,
            String authorizeUrl,
            String apiBaseUrl,
            String callbackUrl
    ) {
    }

    public record MiniProgram(
            boolean enabled,
            String appId,
            String appSecret,
            String apiBaseUrl
    ) {
    }

    public record Sms(
            String mode,
            int ttlSeconds,
            int cooldownSeconds,
            String webhookUrl,
            String webhookToken
    ) {
        public Sms {
            ttlSeconds = ttlSeconds <= 0 ? 300 : ttlSeconds;
            cooldownSeconds = cooldownSeconds <= 0 ? 60 : cooldownSeconds;
        }
    }
}
