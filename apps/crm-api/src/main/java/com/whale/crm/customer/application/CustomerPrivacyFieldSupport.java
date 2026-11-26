package com.whale.crm.customer.application;

import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyField;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 客户隐私字段统一处理：手机号、身份证号、邮箱、微信号。
 * <p>
 * 强制约束：
 * 1. 落库仅存 AES-256-GCM 密文 + HMAC-SHA256 盲索引；
 * 2. 写入口拒绝包含脱敏占位符 '*'，防止前端将脱敏值误回写覆盖真实密文；
 * 3. 常规读取/列表/导出/审计快照一律只返回脱敏值；
 * 4. {@link #decryptForAuthorizedReveal} 仅允许在经过权限/审批校验且写审计日志的揭盲服务内调用。
 */
@Component
public class CustomerPrivacyFieldSupport {

    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] ID_CARD_CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
    private static final DateTimeFormatter ID_CARD_BIRTH_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-z0-9._%+-]{1,64}@[a-z0-9.-]+\\.[a-z]{2,63}$");
    private static final Pattern WECHAT_PATTERN = Pattern.compile("^[\\p{Graph}]{3,64}$");

    private final MobileSupport mobileSupport;
    private final AesGcmCipher cipher;
    private final BlindIndex blindIndex;

    public CustomerPrivacyFieldSupport(MobileSupport mobileSupport, AesGcmCipher cipher, BlindIndex blindIndex) {
        this.mobileSupport = mobileSupport;
        this.cipher = cipher;
        this.blindIndex = blindIndex;
    }

    public String normalizeMobile(String raw) {
        rejectMaskedWildcard(raw, "手机号");
        return mobileSupport.normalize(raw);
    }

    public String encryptMobile(String normalized) {
        return mobileSupport.encrypt(normalized);
    }

    public String hashMobile(String normalized) {
        return mobileSupport.hash(normalized);
    }

    public String maskMobileFromCipher(String mobileCipher) {
        return mobileSupport.maskFromCipher(mobileCipher);
    }

    public String normalizeIdCard(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        rejectMaskedWildcard(raw, "身份证号");
        String normalized = raw.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("^\\d{17}[0-9X]$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证号需为 18 位有效二代身份证号码");
        }
        String birthText = normalized.substring(6, 14);
        try {
            LocalDate birthDate = LocalDate.parse(birthText, ID_CARD_BIRTH_FORMAT);
            if (birthDate.isAfter(LocalDate.now()) || birthDate.getYear() < 1900) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证号出生日期无效");
            }
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证号出生日期无效");
        }
        int sum = 0;
        for (int i = 0; i < ID_CARD_WEIGHTS.length; i++) {
            sum += Character.getNumericValue(normalized.charAt(i)) * ID_CARD_WEIGHTS[i];
        }
        if (ID_CARD_CHECK_CODES[sum % 11] != normalized.charAt(17)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "身份证号校验码无效");
        }
        return normalized;
    }

    public String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        rejectMaskedWildcard(raw, "邮箱");
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 128 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "邮箱格式无效");
        }
        return normalized;
    }

    public String normalizeWechatId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        rejectMaskedWildcard(raw, "微信号");
        String normalized = raw.strip();
        if (!WECHAT_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "微信号需为 3-64 位不含空格的有效字符");
        }
        return normalized;
    }

    public String encrypt(String normalized) {
        return normalized == null ? null : cipher.encrypt(normalized);
    }

    public String hashIdCard(String normalized) {
        return normalized == null ? null : blindIndex.hash("idcard:" + normalized);
    }

    public String hashEmail(String normalized) {
        return normalized == null ? null : blindIndex.hash("email:" + normalized);
    }

    public String hashWechatId(String normalized) {
        return normalized == null ? null : blindIndex.hash("wechat:" + normalized);
    }

    public String maskIdCardFromCipher(String idCardCipher) {
        return idCardCipher == null ? null : maskIdCard(cipher.decrypt(idCardCipher));
    }

    public String maskEmailFromCipher(String emailCipher) {
        return emailCipher == null ? null : maskEmail(cipher.decrypt(emailCipher));
    }

    public String maskWechatIdFromCipher(String wechatIdCipher) {
        return wechatIdCipher == null ? null : maskWechatId(cipher.decrypt(wechatIdCipher));
    }

    public String maskFromCipher(CustomerPrivacyField field, String cipherValue) {
        if (cipherValue == null) {
            return null;
        }
        return switch (field) {
            case MOBILE -> maskMobileFromCipher(cipherValue);
            case ID_CARD -> maskIdCardFromCipher(cipherValue);
            case EMAIL -> maskEmailFromCipher(cipherValue);
            case WECHAT_ID -> maskWechatIdFromCipher(cipherValue);
        };
    }

    public boolean hasValue(Customer customer, CustomerPrivacyField field) {
        return cipherOf(customer, field) != null;
    }

    public String maskForCustomer(Customer customer, CustomerPrivacyField field) {
        return maskFromCipher(field, cipherOf(customer, field));
    }

    /**
     * 仅限隐私揭盲服务在完成权限校验与审计落盘的同一事务内调用。
     */
    public String decryptForAuthorizedReveal(Customer customer, CustomerPrivacyField field) {
        String cipherValue = cipherOf(customer, field);
        return cipherValue == null ? null : cipher.decrypt(cipherValue);
    }

    /**
     * 按搜索关键词计算精确盲索引候选哈希，用于列表关键词等值匹配（不做明文 LIKE）。
     */
    public PrivacySearchHashes extractSearchHashes(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.contains("*")) {
            return new PrivacySearchHashes(null, null, null, null);
        }
        String trimmed = keyword.strip();
        String mobileHash = mobileSupport.looksLikeMobile(trimmed)
                ? mobileSupport.hash(mobileSupport.normalize(trimmed)) : null;
        String idCardHash = null;
        if (trimmed.matches("(?i)^\\d{17}[0-9X]$")) {
            try {
                idCardHash = hashIdCard(normalizeIdCard(trimmed));
            } catch (BusinessException ignored) {
                idCardHash = null;
            }
        }
        String emailHash = null;
        if (trimmed.contains("@") && trimmed.length() <= 128) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (EMAIL_PATTERN.matcher(lower).matches()) {
                emailHash = hashEmail(lower);
            }
        }
        String wechatHash = WECHAT_PATTERN.matcher(trimmed).matches() ? hashWechatId(trimmed) : null;
        return new PrivacySearchHashes(mobileHash, idCardHash, emailHash, wechatHash);
    }

    public static String maskIdCard(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String value = plain.strip();
        if (value.length() >= 11) {
            return value.substring(0, 6) + "*".repeat(value.length() - 10) + value.substring(value.length() - 4);
        }
        return "****";
    }

    public static String maskEmail(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String value = plain.strip();
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return "***@***";
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 1) {
            return "*" + "***" + domain;
        }
        if (local.length() <= 3) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    public static String maskWechatId(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        String value = plain.strip();
        int len = value.length();
        if (len >= 6) {
            return value.substring(0, 2) + "****" + value.substring(len - 2);
        }
        if (len >= 3) {
            return value.substring(0, 1) + "***" + value.substring(len - 1);
        }
        return "*".repeat(len);
    }

    public static String maskRawLegacyMobile(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = raw.strip().replaceAll("[\\s-]+", "");
        if (compact.matches("^\\+?\\d{5,15}$")) {
            return MobileSupport.mask(compact);
        }
        return maskWechatId(compact);
    }

    private static void rejectMaskedWildcard(String value, String label) {
        if (value != null && value.contains("*")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能包含脱敏占位符 *");
        }
    }

    private static String cipherOf(Customer customer, CustomerPrivacyField field) {
        if (customer == null || field == null) {
            return null;
        }
        return switch (field) {
            case MOBILE -> customer.getMobileCipher();
            case ID_CARD -> customer.getIdCardCipher();
            case EMAIL -> customer.getEmailCipher();
            case WECHAT_ID -> customer.getWechatIdCipher();
        };
    }

    public record PrivacySearchHashes(
            String mobileHash,
            String idCardHash,
            String emailHash,
            String wechatIdHash) {
    }
}
