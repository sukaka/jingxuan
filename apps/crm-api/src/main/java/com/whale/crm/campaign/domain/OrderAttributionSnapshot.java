package com.whale.crm.campaign.domain;

public record OrderAttributionSnapshot(Long campaignId, Long campaignBatchId,
                                       String campaignCodeSnapshot, String campaignNameSnapshot,
                                       String batchCodeSnapshot, String batchNameSnapshot,
                                       Long channelId, Long businessOwnerAtSaleId) {
    public static OrderAttributionSnapshot empty() {
        return new OrderAttributionSnapshot(null, null, null, null, null, null, null, null);
    }
}
