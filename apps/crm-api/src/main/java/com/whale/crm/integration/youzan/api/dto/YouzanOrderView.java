package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanOrderView(Long mapId, String shopId, String externalOrderNo, Long orderId,
                              String status, Long paidFen, String sourceSystem, String externalShopId,
                              String eventId, String mappingStatus, String orderStatus, Instant sourceUpdatedAt,
                              Long externalAmountFen, String verificationMethod, String payloadMaskedJson,
                              String errorReason) {
}
