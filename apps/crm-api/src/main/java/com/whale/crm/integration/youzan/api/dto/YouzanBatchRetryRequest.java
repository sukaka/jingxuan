package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YouzanBatchRetryRequest(
        @NotBlank @Size(max = 64) String shopId,
        @Size(max = 128) String externalSkuId,
        @Min(1) @Max(100) Integer limit
) {
}
