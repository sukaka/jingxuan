package com.whale.crm.reporting.domain;

import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportWindowTest {

    @Test
    void convertsInclusiveLocalDatesToUtcUsingDstAwareZoneRules() {
        ReportWindow window = ReportWindow.of(
                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 9), "America/New_York");

        assertThat(window.fromUtc()).isEqualTo(Instant.parse("2026-03-07T05:00:00Z"));
        assertThat(window.toUtcExclusive()).isEqualTo(Instant.parse("2026-03-10T04:00:00Z"));
        assertThat(window.timeZone()).isEqualTo("America/New_York");
        assertThat(window.periodDays()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidZoneInvertedRangeAndMoreThan366Days() {
        assertThatThrownBy(() -> ReportWindow.of(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2), "Mars/Base"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReportWindow.of(LocalDate.of(2026, 1, 3),
                LocalDate.of(2026, 1, 2), "UTC"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReportWindow.of(LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 1, 2), "UTC"))
                .isInstanceOf(BusinessException.class);
    }
}
