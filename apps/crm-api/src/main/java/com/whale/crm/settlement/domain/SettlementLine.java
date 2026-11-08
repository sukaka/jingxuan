package com.whale.crm.settlement.domain;

import java.time.Instant;

public record SettlementLine(Long id, Long settlementBatchId, Long redemptionId, String amountStatus,
                             Long grossFen, Long commissionFen, Long payableFen, String rateSnapshot,
                             String pendingReason, Instant createdAt) {
}
