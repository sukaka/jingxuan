package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanOrderMap(Long id, String shopId, String externalOrderNo, Long crmOrderId,
                             Instant lastExternalUpdatedAt, Instant createdAt, Long version) {
}
