package com.whale.crm.customer.application;

import com.whale.crm.customer.application.CustomerTimelineCursor.CursorMode;
import com.whale.crm.customer.application.CustomerTimelineCursor.SqlCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerTimelineCursorTest {

    private static final Instant BASE = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void sourceIdParsesTwentyDigitPrefixAndRejectsMalformedKeys() {
        CustomerTimelineCursor valid = new CustomerTimelineCursor(BASE, "FOLLOWUP", "00000000000000000123:FOLLOWUP");
        assertThat(valid.sourceId()).isEqualTo(123L);

        assertThat(new CustomerTimelineCursor(BASE, "FOLLOWUP", null).sourceId()).isNull();
        assertThat(new CustomerTimelineCursor(BASE, "FOLLOWUP", "123:FOLLOWUP").sourceId()).isNull();
        assertThat(new CustomerTimelineCursor(BASE, "FOLLOWUP", "0000000000000000012a:FOLLOWUP").sourceId()).isNull();
        assertThat(new CustomerTimelineCursor(BASE, "FOLLOWUP", "00000000000000000123-FOLLOWUP").sourceId()).isNull();
    }

    @Test
    void sqlCursorMatchesLexicographicalTypeOrderAcrossAllTwelveTypes() {
        assertThat(CustomerTimelineCursor.sqlCursor(null, "ORDER"))
                .isEqualTo(new SqlCursor(CursorMode.ALL, null, null));

        List<String> descTypes = List.of(
                "TIER", "TASK", "REFUND", "REDEMPTION", "OWNERSHIP", "ORDER",
                "NEXT_FOLLOWUP", "INTENT", "FOLLOWUP", "COMPLAINT", "BLOCKER", "APPOINTMENT");
        CustomerTimelineCursor complaintCursor = new CustomerTimelineCursor(
                BASE, "COMPLAINT", "00000000000000000042:COMPLAINT");

        for (String type : descTypes) {
            SqlCursor sql = CustomerTimelineCursor.sqlCursor(complaintCursor, type);
            int cmp = type.compareTo("COMPLAINT");
            if (cmp < 0) {
                assertThat(sql).isEqualTo(new SqlCursor(CursorMode.LE, BASE, null));
            } else if (cmp == 0) {
                assertThat(sql).isEqualTo(new SqlCursor(CursorMode.EQ, BASE, 42L));
            } else {
                assertThat(sql).isEqualTo(new SqlCursor(CursorMode.LT, BASE, null));
            }
        }

        // Verify FOLLOWUP is LT and BLOCKER is LE relative to COMPLAINT
        assertThat(CustomerTimelineCursor.sqlCursor(complaintCursor, "FOLLOWUP").mode()).isEqualTo(CursorMode.LT);
        assertThat(CustomerTimelineCursor.sqlCursor(complaintCursor, "BLOCKER").mode()).isEqualTo(CursorMode.LE);
        assertThat(CustomerTimelineCursor.sqlCursor(complaintCursor, "OWNERSHIP").mode()).isEqualTo(CursorMode.LT);
        assertThat(CustomerTimelineCursor.sqlCursor(complaintCursor, "ORDER").mode()).isEqualTo(CursorMode.LT);
        assertThat(CustomerTimelineCursor.sqlCursor(complaintCursor, "APPOINTMENT").mode()).isEqualTo(CursorMode.LE);

        // Malformed eventKey falls back safely to LE on EQ branch
        CustomerTimelineCursor malformed = new CustomerTimelineCursor(BASE, "TASK", "legacy-key");
        assertThat(CustomerTimelineCursor.sqlCursor(malformed, "TASK"))
                .isEqualTo(new SqlCursor(CursorMode.LE, BASE, null));
    }
}
