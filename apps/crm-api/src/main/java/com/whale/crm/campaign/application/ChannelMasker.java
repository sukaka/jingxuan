package com.whale.crm.campaign.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

public final class ChannelMasker {
    private ChannelMasker() {
    }

    public static String maskPhone(String plain) {
        if (plain == null) {
            return null;
        }
        int len = plain.length();
        if (len >= 7) {
            return plain.substring(0, 3) + "****" + plain.substring(len - 4);
        }
        return "****";
    }

    public static String maskIdCard(String plain) {
        if (plain == null) {
            return null;
        }
        int len = plain.length();
        if (len >= 11) {
            return plain.substring(0, 6) + "*".repeat(len - 10) + plain.substring(len - 4);
        }
        return "****";
    }

    public static String maskAccountName(String plain) {
        if (plain == null) {
            return null;
        }
        int len = plain.length();
        if (len <= 1) {
            return "*";
        }
        if (len == 2) {
            return plain.charAt(0) + "*";
        }
        return plain.charAt(0) + "*".repeat(len - 2) + plain.charAt(len - 1);
    }

    public static String maskAccountNo(String plain) {
        if (plain == null) {
            return null;
        }
        int len = plain.length();
        if (len > 4) {
            return "****" + plain.substring(len - 4);
        }
        return "****";
    }

    public static void rejectMaskedWildcard(String value, String label) {
        if (value != null && value.contains("*")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能包含脱敏占位符 *");
        }
    }
}
