package com.whale.crm.quickreply.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class QuickReplyTextMasker {
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?<![0-9A-Za-z])\\d{6}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?![0-9A-Za-z])");
    private static final Pattern LONG_ACCOUNT = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");

    public String required(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空");
        }
        String normalized = value.strip();
        boolean invalidControl = normalized.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t');
        if (normalized.length() > maxLength || invalidControl) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "长度或字符无效");
        }
        return mask(normalized, maxLength);
    }

    public String mask(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String input = value.length() > maxLength ? value.substring(0, maxLength) : value;
        String masked = PHONE.matcher(input).replaceAll("$1****$2");
        masked = EMAIL.matcher(masked).replaceAll("***@***");
        masked = ID_CARD.matcher(masked).replaceAll("******************");
        return LONG_ACCOUNT.matcher(masked).replaceAll("****");
    }
}
