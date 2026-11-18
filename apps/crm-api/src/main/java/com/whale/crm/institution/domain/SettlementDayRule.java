package com.whale.crm.institution.domain;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.regex.Pattern;

public final class SettlementDayRule {
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    public static final String BUILT_IN_DEFAULT = "LAST_DAY";
    private static final Pattern DAY = Pattern.compile("([1-9]|1[0-9]|2[0-8])");

    private SettlementDayRule() {
    }

    public static String normalize(String value) {
        if (value == null) {
            throw invalid();
        }
        String normalized = value.strip();
        if (!"LAST_DAY".equals(normalized) && !DAY.matcher(normalized).matches()) {
            throw invalid();
        }
        return normalized;
    }

    public static String normalizeNullable(String value) {
        return value == null ? null : normalize(value);
    }

    public static LocalDate dateFor(YearMonth period, String rule) {
        String normalized = normalize(rule);
        int day = "LAST_DAY".equals(normalized) ? period.lengthOfMonth() : Integer.parseInt(normalized);
        return period.atDay(day);
    }

    public static MonthlySnapshot monthlySnapshot(YearMonth period, String defaultDay, String institutionDay) {
        String normalizedDefault = normalize(defaultDay);
        String normalizedInstitution = normalizeNullable(institutionDay);
        String effective = normalizedInstitution == null ? normalizedDefault : normalizedInstitution;
        return new MonthlySnapshot(effective, normalizedInstitution == null ? "DEFAULT" : "INSTITUTION",
                dateFor(period, effective));
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "结算日必须为1至28或LAST_DAY");
    }

    public record MonthlySnapshot(String rule, String source, LocalDate settlementDate) {
    }
}
