package com.whale.crm.integration.youzan.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record YouzanOrderEvent(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("shop_id") String shopId,
        @JsonProperty("external_order_no") String externalOrderNo,
        @JsonProperty("external_customer_id") String externalCustomerId,
        @JsonProperty("external_trade_no") String externalTradeNo,
        @JsonProperty("status") String status,
        @JsonProperty("amount_fen") Long amountFen,
        @JsonProperty("paid_at") Instant paidAt,
        @JsonProperty("external_updated_at") Instant externalUpdatedAt,
        @JsonProperty("order_type_code") String orderTypeCode,
        @JsonProperty("campaign_id") Long campaignId,
        @JsonProperty("campaign_batch_id") Long campaignBatchId,
        @JsonProperty("receiver_type") String receiverType,
        @JsonProperty("valid_to") LocalDate validTo,
        @JsonProperty("items") List<YouzanOrderLine> items) {
    public YouzanOrderEvent {
        items = items == null ? null : List.copyOf(items);
    }
}
