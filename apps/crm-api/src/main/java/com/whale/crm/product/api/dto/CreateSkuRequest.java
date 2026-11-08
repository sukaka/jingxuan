package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSkuRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,96}") String skuCode,
        @NotNull Long packageVersionId,
        @NotBlank @Size(max = 128) String specName,
        @NotNull @Min(0) Long salePriceFen,
        @NotNull List<Long> institutionIds) {
    public CreateSkuRequest {
        institutionIds = institutionIds == null ? null : List.copyOf(institutionIds);
    }
}
