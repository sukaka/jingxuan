package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomCursor(Long id, String corpId, String archiveScope, Long lastSeq, Instant lastSuccessAt,
                          Instant lastAttemptAt, String status, Integer consecutiveFailures, String lastError,
                          Boolean dataGap, String gapReason, Long version) {
}
