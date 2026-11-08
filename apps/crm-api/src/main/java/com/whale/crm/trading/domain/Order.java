package com.whale.crm.trading.domain;

import java.time.Instant;

public record Order(Long id, String orderNo, Long customerId, String customerName, Long soldByUserId,
                    Long ownerAtSaleId, String externalOrderNo, String sourceSystem, String externalShopId,
                    String orderTypeCode, String orderTypeLabelSnapshot,
                    Long campaignId, Long campaignBatchId,
                    String campaignCodeSnapshot, String campaignNameSnapshot,
                    String batchCodeSnapshot, String batchNameSnapshot,
                    Long channelId, Long businessOwnerAtSaleId,
                    String status, String receiverType,
                    Long listAmountFen, Long discountAmountFen, Long payableFen, Long paidFen,
                    Long refundedFen, Instant orderedAt, Instant paidAt, Instant createdAt, Long version) {
    public Order(Long id, String orderNo, Long customerId, String customerName, Long soldByUserId,
                 Long ownerAtSaleId, String externalOrderNo, String sourceSystem, String externalShopId,
                 String orderTypeCode, Long campaignId, Long campaignBatchId, String status, String receiverType,
                 Long listAmountFen, Long discountAmountFen, Long payableFen, Long paidFen,
                 Long refundedFen, Instant orderedAt, Instant paidAt, Instant createdAt, Long version) {
        this(id, orderNo, customerId, customerName, soldByUserId, ownerAtSaleId, externalOrderNo, sourceSystem,
                externalShopId, orderTypeCode, null, campaignId, campaignBatchId, null, null, null, null, null, null,
                status, receiverType, listAmountFen, discountAmountFen, payableFen, paidFen, refundedFen,
                orderedAt, paidAt, createdAt, version);
    }
}
