package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * 客户年终 H5 访问与转化只追加事件实体。
 */
public record AnnualReportVisitEvent(
        Long id,
        long tenantId,
        long reportId,
        long sessionId,
        long customerId,
        String eventType,
        int sceneIndex,
        int deltaDurationMs,
        String detailJson,
        Instant occurredAt,
        Instant createdAt
) {
}
