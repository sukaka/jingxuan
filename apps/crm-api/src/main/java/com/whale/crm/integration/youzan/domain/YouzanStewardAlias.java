package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanStewardAlias(
        Long id,
        Long tenantId,
        String aliasName,
        String aliasKey,
        Long stewardUserId,
        String status,
        String remark,
        Long createdBy,
        Long updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
}
