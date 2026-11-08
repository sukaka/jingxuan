package com.whale.crm.trading.domain;

import java.util.List;

public record FulfillmentEntitlementView(Entitlement entitlement, Long orderId,
                                         List<Long> institutionIds, String allocationStatus) {
    public FulfillmentEntitlementView {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }
}
