package com.whale.crm.customer.privacy.domain;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.util.Locale;

/**
 * 受严格脱敏与审批/显式揭盲管控的客户隐私字段枚举。
 */
public enum CustomerPrivacyField {

    MOBILE("手机号"),
    ID_CARD("身份证号"),
    EMAIL("邮箱"),
    WECHAT_ID("微信号");

    private final String label;

    CustomerPrivacyField(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static CustomerPrivacyField fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "隐私字段类型不能为空");
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        for (CustomerPrivacyField field : values()) {
            if (field.name().equals(normalized)) {
                return field;
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的隐私字段类型：" + raw);
    }
}
