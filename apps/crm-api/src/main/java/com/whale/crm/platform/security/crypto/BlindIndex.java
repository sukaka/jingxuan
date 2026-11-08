package com.whale.crm.platform.security.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import com.whale.crm.platform.security.CryptoProperties;
import org.springframework.stereotype.Component;

/**
 * 盲索引（HMAC-SHA256(pepper, normalized)）：为 `_cipher` 密文列配套生成 `_hash` 等值查询索引。
 * 只做等值匹配，不支持范围/模糊查询；pepper 与 AES 密钥分离，轮换需重建索引列。
 */
@Component
public class BlindIndex {

    private final byte[] pepper;

    public BlindIndex(CryptoProperties properties) {
        this.pepper = properties.blindIndexPepper().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @param normalized 已归一化的明文（如手机号去空格/连字符）；归一化规则由调用方固定，变更即换索引。
     */
    public String hash(String normalized) {
        if (normalized == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("盲索引计算失败", ex);
        }
    }
}
