package com.whale.crm.fulfillment.api.dto;

import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.fulfillment.domain.RedemptionReversalHistory;
import com.whale.crm.trading.domain.EntitlementCorrectionView;

import java.util.List;

public record ReversalDetail(RedemptionReversal reversal, List<RedemptionReversalHistory> history,
                             List<EntitlementCorrectionView> corrections) {
    public ReversalDetail {
        history = history == null ? null : List.copyOf(history);
        corrections = corrections == null ? null : List.copyOf(corrections);
    }
}
