package com.whale.crm.integration.wecom.tag.api.dto;

import java.time.Instant;

/**
 * 企微标签双向同步运行批次响应。
 */
public record WecomTagSyncRunResponse(
        Long id,
        String corpId,
        String syncScope,
        String direction,
        String triggerSource,
        String adapterName,
        String status,
        int pulledCount,
        int pushedCount,
        int skippedCount,
        int failedCount,
        String errorMasked,
        Instant startedAt,
        Instant finishedAt) {
}
