package com.whale.crm.integration.youzan.api.dto;

public record YouzanBatchRetryResponse(
        String shopId,
        String externalSkuId,
        int matched,
        int attempted,
        int processed,
        int advanced,
        int stillFailed,
        int skipped,
        int remaining
) {
}
