package com.whale.crm.settlement.domain;

import java.util.List;

public record SettlementScope(List<Long> institutionIds) {
    public SettlementScope {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }

    public static SettlementScope all() {
        return new SettlementScope(null);
    }

    public static SettlementScope restricted(List<Long> institutionIds) {
        return new SettlementScope(List.copyOf(institutionIds));
    }
}
