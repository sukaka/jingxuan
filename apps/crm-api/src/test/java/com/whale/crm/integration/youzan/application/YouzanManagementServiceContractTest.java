package com.whale.crm.integration.youzan.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YouzanManagementServiceContractTest {
    @Test
    void syncCompletedEventIdIsDeterministicBoundedAndCursorSpecific() {
        String shop = "s".repeat(64);
        String cursor = "{\"queryType\":\"CREATED\",\"windowStart\":\"2026-01-01T00:00:00.123456789Z\","
                + "\"windowEnd\":\"2026-03-01T00:00:00.123456789Z\",\"finalEnd\":\"2026-10-26T00:00:00.123456789Z\",\"pageNo\":100}";

        String first = YouzanManagementService.syncCompletedEventId(shop, cursor);

        assertThat(first).isEqualTo(YouzanManagementService.syncCompletedEventId(shop, cursor));
        assertThat(first).isNotEqualTo(YouzanManagementService.syncCompletedEventId(shop, cursor + " "));
        assertThat(first.length()).isLessThan(192);
        assertThat(first).doesNotContain("queryType");
    }
}
