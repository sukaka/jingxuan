package com.whale.crm.integration.wecom.api.dto;

import java.time.Instant;

public record WecomMonitorView(String corpId, String archiveScope, long lastSeq, Instant lastSuccessAt,
                               Instant lastAttemptAt, String status, boolean dataGap, String gapReason,
                               long messageCount, long unmatchedCount, long activeGaps, long historicalGaps) {
}
