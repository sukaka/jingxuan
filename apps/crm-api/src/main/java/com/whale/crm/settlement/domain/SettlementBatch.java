package com.whale.crm.settlement.domain;

import java.time.Instant;

public record SettlementBatch(Long id, Long institutionId, String settlementType, String periodKey,
                              String status, String amountStatus, Long grossFen, Long commissionFen,
                              Long netPayableFen, Long paidFen, String ruleVersion, String verificationEvidence,
                              Instant createdAt, Instant updatedAt, Long version) {
}
