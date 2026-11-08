package com.whale.crm.settlement.api.dto;

import com.whale.crm.settlement.domain.SettlementBatch;
import com.whale.crm.settlement.domain.SettlementLine;
import com.whale.crm.settlement.domain.SettlementPayment;

import java.util.List;

public record SettlementBatchDetail(SettlementBatch batch, List<SettlementLine> lines,
                                    List<SettlementPayment> payments) {
    public SettlementBatchDetail {
        lines = lines == null ? null : List.copyOf(lines);
        payments = payments == null ? null : List.copyOf(payments);
    }
}
