package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanSkuCandidateView(
        String shopId,
        String externalSkuId,
        String itemTitle,
        String skuSpec,
        Long priceFen,
        String outerSkuCode,
        int pendingIssueCount,
        String sampleOrderNo,
        Instant latestEventAt,
        boolean mapped,
        Long crmSkuId
) {
}
