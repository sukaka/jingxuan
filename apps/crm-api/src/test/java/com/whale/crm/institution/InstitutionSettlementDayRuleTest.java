package com.whale.crm.institution;

import com.whale.crm.institution.domain.SettlementDayRule;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstitutionSettlementDayRuleTest {
    @Test
    void institutionOverrideWinsAndNullInheritsDefault() {
        SettlementDayRule.MonthlySnapshot inherited = SettlementDayRule.monthlySnapshot(
                YearMonth.of(2026, 10), "LAST_DAY", null);
        SettlementDayRule.MonthlySnapshot overridden = SettlementDayRule.monthlySnapshot(
                YearMonth.of(2026, 10), "LAST_DAY", "8");

        assertThat(inherited.rule()).isEqualTo("LAST_DAY");
        assertThat(inherited.source()).isEqualTo("DEFAULT");
        assertThat(inherited.settlementDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(overridden.rule()).isEqualTo("8");
        assertThat(overridden.source()).isEqualTo("INSTITUTION");
        assertThat(overridden.settlementDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void lastDayHandlesLeapYearsAndShortMonthsInShanghaiBusinessCalendar() {
        assertThat(SettlementDayRule.ZONE_ID).isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(SettlementDayRule.dateFor(YearMonth.of(2024, 2), "LAST_DAY"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(SettlementDayRule.dateFor(YearMonth.of(2026, 4), "LAST_DAY"))
                .isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(SettlementDayRule.dateFor(YearMonth.of(2026, 2), "28"))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void acceptsOnlyOneToTwentyEightOrExplicitLastDay() {
        for (int day = 1; day <= 28; day++) {
            assertThat(SettlementDayRule.normalize(Integer.toString(day))).isEqualTo(Integer.toString(day));
        }
        assertThat(SettlementDayRule.normalize("LAST_DAY")).isEqualTo("LAST_DAY");
        assertThat(SettlementDayRule.normalizeNullable(null)).isNull();

        for (String invalid : new String[]{"0", "29", "01", "1.0", "last_day", "", " "}) {
            assertThatThrownBy(() -> SettlementDayRule.normalize(invalid))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("1至28");
        }
        assertThatThrownBy(() -> SettlementDayRule.normalize(null))
                .isInstanceOf(BusinessException.class);
    }
}
