package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SkuReviewRequest(@NotNull @Min(0) Long version) {
}
