package com.whale.crm.annualreport.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 有赞历史订单只读快照拉取结果汇总。
 */
public record YouzanAnnualSnapshotSyncResponse(
        String shopId,
        String startDate,
        String endDate,
        int windowsPulled,
        int ordersSynced,
        int paidOrdersSynced,
        int buyersSynced,
        int buyersEnriched,
        int openIdBoundCount,
        int unionIdBoundCount,
        int linkedCustomerCount,
        List<String> warnings,
        Instant syncedAt
) {
    public YouzanAnnualSnapshotSyncResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
