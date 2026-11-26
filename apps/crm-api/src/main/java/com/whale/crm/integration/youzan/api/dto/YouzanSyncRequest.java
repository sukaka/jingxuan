package com.whale.crm.integration.youzan.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record YouzanSyncRequest(@NotBlank @Size(max = 64) String shopId,
                                @Size(max = 2048) String cursor,
                                @Min(1) @Max(100) Integer limit,
                                @Min(1) @Max(500) Integer maxOrders) {
    public YouzanSyncRequest(String shopId, String cursor, Integer limit) {
        this(shopId, cursor, limit, null);
    }
}
