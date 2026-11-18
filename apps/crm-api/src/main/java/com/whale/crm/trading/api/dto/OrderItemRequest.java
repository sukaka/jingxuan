package com.whale.crm.trading.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record OrderItemRequest(
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1000) Integer quantity,
        @Valid @Size(max = 1000) List<OrderItemSelectionRequest> selections) {
    public OrderItemRequest {
        selections = selections == null ? null : List.copyOf(selections);
    }

    public OrderItemRequest(Long skuId, Integer quantity) {
        this(skuId, quantity, null);
    }
}
