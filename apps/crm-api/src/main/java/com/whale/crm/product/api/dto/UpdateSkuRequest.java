package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateSkuRequest(
        @NotBlank @Size(max = 128) String specName,
        @NotNull @Min(0) Long salePriceFen,
        @NotNull List<Long> institutionIds,
        @NotNull @Min(0) Long version) {
    public UpdateSkuRequest {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }
}
