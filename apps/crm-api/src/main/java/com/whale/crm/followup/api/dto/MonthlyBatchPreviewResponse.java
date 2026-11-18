package com.whale.crm.followup.api.dto;

import java.util.List;

public record MonthlyBatchPreviewResponse(String orderMonth, String dispatchMonth,
                                          List<MonthlyBatchCandidateResponse> items,
                                          long total, int page, int size) {
    public MonthlyBatchPreviewResponse {
        items = items == null ? null : List.copyOf(items);
    }
}
