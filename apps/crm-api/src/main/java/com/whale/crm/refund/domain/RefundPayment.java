package com.whale.crm.refund.domain;

import java.time.Instant;

public record RefundPayment(Long id, Long tenantId, Long refundId, String provider, String externalRefundNo,
                            Long amountFen, String status, String verificationMethod, String evidenceRef,
                            Long voucherFileId, Instant succeededAt, Instant createdAt, Instant updatedAt, Long version) { }
