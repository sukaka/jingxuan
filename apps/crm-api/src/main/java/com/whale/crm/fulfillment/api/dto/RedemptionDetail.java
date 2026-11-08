package com.whale.crm.fulfillment.api.dto;

import com.whale.crm.fulfillment.domain.Redemption;
import com.whale.crm.fulfillment.domain.RedemptionLine;
import com.whale.crm.fulfillment.domain.RedemptionStatusHistory;

import java.util.List;

public record RedemptionDetail(Redemption redemption, List<RedemptionLine> lines,
                               List<RedemptionStatusHistory> history) {
    public RedemptionDetail {
        lines = lines == null ? null : List.copyOf(lines);
        history = history == null ? null : List.copyOf(history);
    }
}
