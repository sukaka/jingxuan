package com.whale.crm.customer.application;

import java.util.List;

public record MergeOrderResourceSnapshot(List<Long> orderIds, List<Long> entitlementIds) {
    public MergeOrderResourceSnapshot {
        orderIds = orderIds == null ? null : List.copyOf(orderIds);
        entitlementIds = entitlementIds == null ? null : List.copyOf(entitlementIds);
    }
}
