package com.whale.crm.integration.youzan.api.dto;

import java.time.Instant;

public record YouzanStewardAliasView(
        Long id,
        String aliasName,
        String aliasKey,
        Long stewardUserId,
        String stewardName,
        Long teamId,
        String teamName,
        String status,
        String remark,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
}
