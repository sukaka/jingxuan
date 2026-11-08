package com.whale.crm.trading.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateOrderRequest(
        @NotNull Long customerId,
        @NotBlank @Pattern(regexp = "PLATFORM|INSTITUTION") String receiverType,
        @NotBlank @Size(max = 48) String orderTypeCode,
        Long campaignId,
        Long campaignBatchId,
        @NotNull @Min(1) Long payableFen,
        LocalDate validTo,
        @NotEmpty List<@Valid OrderItemRequest> items,
        Long channelId) {
    public CreateOrderRequest {
        items = items == null ? null : List.copyOf(items);
    }

    public CreateOrderRequest(Long customerId, String receiverType, String orderTypeCode,
                              Long campaignId, Long campaignBatchId, Long payableFen,
                              LocalDate validTo, List<OrderItemRequest> items) {
        this(customerId, receiverType, orderTypeCode, campaignId, campaignBatchId, payableFen, validTo, items, null);
    }
}
