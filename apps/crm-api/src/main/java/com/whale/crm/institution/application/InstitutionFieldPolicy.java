package com.whale.crm.institution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.math.BigDecimal;
import java.util.Set;

public final class InstitutionFieldPolicy {
    private static final Set<String> CIPHER_FIELDS = Set.of("bankName", "bankAccount", "taxId", "settlementName");

    private InstitutionFieldPolicy() {
    }

    public static String firstStep(String fieldCode) {
        return switch (fieldCode) {
            case "bankName", "bankAccount", "taxId", "settlementName" -> "FINANCE";
            case "onlineRate", "offlineRate", "platformRate", "otherRateRule", "chargesFee", "depositFen",
                    "guaranteeRoi", "roiTerms", "settlementType" -> "MARKET";
            case "fullName", "businessTypeCode", "ownershipCode", "gradeCode" -> "MARKET";
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "字段不在机构变更白名单内");
        };
    }

    public static boolean bothSteps(String fieldCode) {
        return !Set.of("bankName", "bankAccount", "taxId", "settlementName", "fullName",
                "businessTypeCode", "ownershipCode", "gradeCode").contains(fieldCode);
    }

    public static boolean cipherField(String fieldCode) {
        return CIPHER_FIELDS.contains(fieldCode);
    }

    public static Object currentValue(InstitutionProfile profile, String fieldCode, InstitutionSupport support) {
        firstStep(fieldCode);
        return switch (fieldCode) {
            case "fullName" -> profile.fullName();
            case "businessTypeCode" -> profile.businessTypeCode();
            case "ownershipCode" -> profile.ownershipCode();
            case "gradeCode" -> profile.gradeCode();
            case "bankName" -> support.decrypt(profile.bankNameCipher());
            case "bankAccount" -> support.decrypt(profile.bankAccountCipher());
            case "taxId" -> support.decrypt(profile.taxIdCipher());
            case "settlementName" -> profile.settlementName();
            case "onlineRate" -> profile.onlineRate();
            case "offlineRate" -> profile.offlineRate();
            case "platformRate" -> profile.platformRate();
            case "otherRateRule" -> profile.otherRateRule();
            case "chargesFee" -> profile.chargesFee();
            case "depositFen" -> profile.depositFen();
            case "guaranteeRoi" -> profile.guaranteeRoi();
            case "roiTerms" -> profile.roiTerms();
            case "settlementType" -> profile.settlementType();
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的机构字段");
        };
    }

    public static String normalize(String fieldCode, JsonNode value) {
        firstStep(fieldCode);
        if (value == null || value.isNull()) {
            if (Set.of("bankName", "bankAccount", "taxId", "settlementType", "onlineRate", "offlineRate",
                    "platformRate", "chargesFee", "depositFen", "guaranteeRoi").contains(fieldCode)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该字段不能为空");
            }
            return null;
        }
        if (Set.of("onlineRate", "offlineRate", "platformRate").contains(fieldCode)) {
            if (!value.isNumber()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "佣金率必须为0至1的数字");
            }
            BigDecimal rate = value.decimalValue();
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "佣金率必须位于0至1之间");
            }
            return rate.stripTrailingZeros().toPlainString();
        }
        if ("depositFen".equals(fieldCode)) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "保证金必须为非负整数分");
            }
            return Long.toString(value.longValue());
        }
        if (Set.of("chargesFee", "guaranteeRoi").contains(fieldCode)) {
            if (!value.isBoolean()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "布尔字段必须为true或false");
            }
            return Boolean.toString(value.booleanValue());
        }
        if (!value.isTextual()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "字段必须为字符串");
        }
        String text = value.textValue().strip();
        if (Set.of("fullName", "bankName", "bankAccount", "taxId", "settlementType").contains(fieldCode)
                && text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "字段不能为空");
        }
        if ("settlementType".equals(fieldCode) && !Set.of("MONTHLY", "PER_REDEMPTION").contains(text)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结算类型必须为MONTHLY或PER_REDEMPTION");
        }
        if ("bankAccount".equals(fieldCode) && !text.matches("[0-9A-Za-z-]{6,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "银行账号格式不正确");
        }
        if (text.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "文本字段超长");
        }
        return text;
    }

    public static Object jsonValue(String fieldCode, String normalized) {
        if (normalized == null) {
            return null;
        }
        if (Set.of("onlineRate", "offlineRate", "platformRate").contains(fieldCode)) {
            return new BigDecimal(normalized);
        }
        if ("depositFen".equals(fieldCode)) {
            return Long.valueOf(normalized);
        }
        if (Set.of("chargesFee", "guaranteeRoi").contains(fieldCode)) {
            return Boolean.valueOf(normalized);
        }
        return normalized;
    }

    public static String storageValue(String fieldCode, String normalized, InstitutionSupport support) {
        return normalized == null || !cipherField(fieldCode) ? normalized : support.encrypt(normalized);
    }
}
