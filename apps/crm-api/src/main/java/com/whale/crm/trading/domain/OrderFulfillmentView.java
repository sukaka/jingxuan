package com.whale.crm.trading.domain;

import java.util.List;

public record OrderFulfillmentView(OrderDetail order, List<Entitlement> entitlements) {
    public OrderFulfillmentView {
        entitlements = entitlements == null ? null : List.copyOf(entitlements);
    }
}
