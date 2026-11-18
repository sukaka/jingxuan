package com.whale.crm.customer.api.dto;

import java.util.List;

public record CustomerTimelinePageResponse(List<CustomerTimelineItemResponse> items, String nextCursor) {
    public CustomerTimelinePageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
