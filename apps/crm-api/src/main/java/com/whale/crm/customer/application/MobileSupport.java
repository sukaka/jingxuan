package com.whale.crm.customer.application;

import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 手机号处理：归一化（去空格/连字符）→ AES-256-GCM 密文 + HMAC 盲索引；展示仅脱敏 138****5678。
 * 明文只在请求入口短暂存在，禁止落库/日志/审计快照。
 */
@Component
public class MobileSupport {

    private final AesGcmCipher cipher;
    private final BlindIndex blindIndex;

    public MobileSupport(AesGcmCipher cipher, BlindIndex blindIndex) {
        this.cipher = cipher;
        this.blindIndex = blindIndex;
    }

    /**
     * 归一化并校验：允许 5-15 位数字（可带前导 +）；非法输入 400。
     *
     * @return 归一化后的手机号；入参 null/空白返回 null（手机号可空，C01）
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.strip().replaceAll("[\\s-]+", "");
        if (!normalized.matches("^\\+?\\d{5,15}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手机号格式无效");
        }
        return normalized;
    }

    /**
     * 归一化形式是否像手机号（用于 keyword 触发盲索引等值查，不做明文 LIKE）。
     */
    public boolean looksLikeMobile(String keyword) {
        return keyword != null && keyword.matches("^\\+?\\d{5,15}$");
    }

    public String encrypt(String normalized) {
        return normalized == null ? null : cipher.encrypt(normalized);
    }

    public String hash(String normalized) {
        return normalized == null ? null : blindIndex.hash(normalized);
    }

    /**
     * 密文 → 脱敏展示（解密仅在应用层内存中发生，返回值不含完整明文）。
     */
    public String maskFromCipher(String mobileCipher) {
        if (mobileCipher == null) {
            return null;
        }
        return mask(cipher.decrypt(mobileCipher));
    }

    /**
     * 138****5678：保留前 3 后 4，中间 4 个 *；过短号码整体打码。
     */
    public static String mask(String normalized) {
        if (normalized == null) {
            return null;
        }
        String digits = normalized.startsWith("+") ? normalized.substring(1) : normalized;
        if (digits.length() >= 7) {
            return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
        }
        return "*".repeat(digits.length());
    }
}
