package com.whale.crm.trading.domain;

import java.time.Instant;

public record PaymentTxn(Long id, Long orderId, String paymentChannel, String receiverType,
                         String externalTradeNo, Long amountFen, String status, Instant confirmedAt,
                         Long confirmedBy, Long voucherFileId, String evidenceRef, String verificationMethod,
                         Instant createdAt, Long version) {
}
