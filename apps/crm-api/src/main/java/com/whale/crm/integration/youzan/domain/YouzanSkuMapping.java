package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanSkuMapping(Long id, String shopId, String externalSkuId, Long crmSkuId,
                               String status, Instant createdAt, Long version) {
}
