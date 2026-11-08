package com.whale.crm.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PackageProjectItemRequest(
        @NotNull Long projectId,
        @NotNull @Min(1) Integer grantCount,
        @Min(0) Integer selectionWeight,
        @Min(0) Long allocationPriceFen,
        @Size(max = 48) String optionGroup) {
}
