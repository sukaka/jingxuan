package com.whale.crm.settlement.domain;

import java.time.Instant;
import java.time.LocalDate;

public record SettlementBatch(Long id, Long institutionId, String settlementType, String periodKey,
                              String settlementDay, String settlementDaySource, LocalDate settlementDate,
                              String status, String amountStatus, Long grossFen, Long commissionFen,
                              Long netPayableFen, Long paidFen, String ruleVersion, String verificationEvidence,
                              Instant createdAt, Instant updatedAt, Long version) {
}
