package com.whale.crm.customer.api.dto;

import java.util.List;

/**
 * 客户积分视图。balance = 账本 SUM(delta)（权威）；crm_customer.points_cached 仅为缓存。
 */
public record CustomerPointsResponse(long balance, List<PointsLedgerItemResponse> ledger) {
    public CustomerPointsResponse {
        ledger = ledger == null ? null : List.copyOf(ledger);
    }
}
