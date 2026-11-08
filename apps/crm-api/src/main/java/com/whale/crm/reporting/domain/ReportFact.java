package com.whale.crm.reporting.domain;

import java.time.Instant;

public record ReportFact(String factType, Long sourceId, Long orderId, Long customerId,
                         Long campaignId, Long campaignBatchId, Long skuId, Instant eventAt,
                         Long amountFen, Long quantity, String sourceSystem, String receiverType) {
}
