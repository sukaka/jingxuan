package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;
import java.util.List;

public record YouzanOrderView(
        Long mapId,
        String shopId,
        String externalOrderNo,
        Long orderId,
        String status,
        Long paidFen,
        String sourceSystem,
        String externalShopId,
        String eventId,
        String mappingStatus,
        String orderStatus,
        Instant sourceUpdatedAt,
        Long externalAmountFen,
        String verificationMethod,
        String payloadMaskedJson,
        String errorReason,
        String externalCustomerId,
        Instant paidAt,
        String orderTypeCode,
        Long campaignId,
        Long campaignBatchId,
        List<YouzanOrderItemView> items,
        String providerOrderStatus,
        String providerStatusText,
        String payWayDesc,
        Instant orderedAt,
        Instant consignedAt,
        String buyerFormNameMasked,
        String buyerFormMobileMasked,
        String tradeMemoMasked,
        String memoPerformanceOwner,
        String memoChannel,
        String memoCustomerType,
        String memoSource,
        String memoAccountName,
        String memoInstitutionName,
        String memoExtraNote,
        Long assignedStewardId,
        String assignedStewardName,
        String assignmentStatus,
        String assignmentSource,
        String assignmentReason,
        Instant assignedAt,
        Long assignmentVersion) {

    public YouzanOrderView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public YouzanOrderView(Long mapId, String shopId, String externalOrderNo, Long orderId,
                           String status, Long paidFen, String sourceSystem, String externalShopId,
                           String eventId, String mappingStatus, String orderStatus, Instant sourceUpdatedAt,
                           Long externalAmountFen, String verificationMethod, String payloadMaskedJson,
                           String errorReason, String externalCustomerId, Instant paidAt,
                           String orderTypeCode, Long campaignId, Long campaignBatchId,
                           List<YouzanOrderItemView> items) {
        this(mapId, shopId, externalOrderNo, orderId, status, paidFen, sourceSystem, externalShopId,
                eventId, mappingStatus, orderStatus, sourceUpdatedAt, externalAmountFen, verificationMethod,
                payloadMaskedJson, errorReason, externalCustomerId, paidAt, orderTypeCode, campaignId,
                campaignBatchId, items,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
