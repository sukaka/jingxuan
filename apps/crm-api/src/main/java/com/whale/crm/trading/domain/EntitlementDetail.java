package com.whale.crm.trading.domain;

import java.util.List;

public record EntitlementDetail(Entitlement entitlement, long availableBalance,
                                List<EntitlementLedgerEntry> ledger) {
    public EntitlementDetail {
        ledger = ledger == null ? null : List.copyOf(ledger);
    }
}
