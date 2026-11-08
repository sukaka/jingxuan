package com.whale.crm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用层加密配置（手机号/银行账号等敏感列 AES + 盲索引）。
 * P0-1 仅为开发密钥占位；生产必须由密钥管理服务注入并支持轮换。
 */
@ConfigurationProperties(prefix = "app.security.crypto")
public record CryptoProperties(String aesKeyBase64, String blindIndexPepper) {
}
