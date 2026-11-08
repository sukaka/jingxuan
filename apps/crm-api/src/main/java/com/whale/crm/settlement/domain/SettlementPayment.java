package com.whale.crm.settlement.domain;

import java.time.Instant;

public record SettlementPayment(Long id, Long settlementBatchId, Long amountFen, String bankTradeNo,
                                Instant paidAt, Long voucherFileId, String evidenceRef, Long createdBy,
                                Instant createdAt) {
}
