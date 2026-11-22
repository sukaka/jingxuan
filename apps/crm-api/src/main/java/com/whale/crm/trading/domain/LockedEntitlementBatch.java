package com.whale.crm.trading.domain;

import java.util.Map;

public record LockedEntitlementBatch(Map<Long, Order> ordersById,
                                     Map<Long, FulfillmentEntitlementView> viewsByEntitlementId) {
    public LockedEntitlementBatch {
        ordersById = ordersById == null ? Map.of() : Map.copyOf(ordersById);
        viewsByEntitlementId = viewsByEntitlementId == null ? Map.of() : Map.copyOf(viewsByEntitlementId);
    }
}
