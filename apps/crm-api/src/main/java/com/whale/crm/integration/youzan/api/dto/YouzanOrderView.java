package com.whale.crm.integration.youzan.api.dto;

import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;

import java.time.Instant;
import java.util.List;

public record YouzanOrderView(Long mapId, String shopId, String externalOrderNo, Long orderId,
                              String status, Long paidFen, String sourceSystem, String externalShopId,
                              String eventId, String mappingStatus, String orderStatus, Instant sourceUpdatedAt,
                              Long externalAmountFen, String verificationMethod, String payloadMaskedJson,
                              String errorReason, String externalCustomerId, Instant paidAt,
                              String orderTypeCode, Long campaignId, Long campaignBatchId,
                              List<YouzanOrderLine> items) {
    public YouzanOrderView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
