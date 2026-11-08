package com.whale.crm.trading.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderItemRequest(
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1000) Integer quantity) {
}
