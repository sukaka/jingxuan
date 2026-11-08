package com.whale.crm.reporting.domain;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public record ReportWindow(LocalDate from, LocalDate to, String timeZone, Instant fromUtc,
                           Instant toUtcExclusive, long periodDays) {
    public static final long MAX_PERIOD_DAYS = 366;

    public static ReportWindow of(LocalDate from, LocalDate to, String timeZone) {
        if (from == null || to == null) {
            throw invalid("报表必须提供 from 和 to 日期");
        }
        if (from.isAfter(to)) {
            throw invalid("报表 from 不能晚于 to");
        }
        long periodDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (periodDays > MAX_PERIOD_DAYS) {
            throw invalid("报表期限不能超过 " + MAX_PERIOD_DAYS + " 天");
        }
        try {
            ZoneId zoneId = ZoneId.of(timeZone);
            Instant fromUtc = from.atStartOfDay(zoneId).toInstant();
            Instant toUtcExclusive = to.plusDays(1).atStartOfDay(zoneId).toInstant();
            return new ReportWindow(from, to, zoneId.getId(), fromUtc, toUtcExclusive, periodDays);
        } catch (DateTimeException ex) {
            throw invalid("报表时区或日期无效");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
