package com.whale.crm.followup.api.dto;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

public record MonthlyBatchResponse(long id, String orderMonth, String dispatchMonth, String status,
                                   long version, int retryCount, Instant nextRetryAt, String lastErrorMasked,
                                   List<MonthlyBatchItemResponse> items) {
    public MonthlyBatchResponse {
        items = items == null ? null : List.copyOf(items);
    }

    public static MonthlyBatchResponse of(long id, String orderMonth, String status, long version, int retryCount,
                                          Instant nextRetryAt, String lastErrorMasked,
                                          List<MonthlyBatchItemResponse> items) {
        return new MonthlyBatchResponse(id, orderMonth, YearMonth.parse(orderMonth).plusMonths(3).toString(),
                status, version, retryCount, nextRetryAt, lastErrorMasked, items);
    }
}
