package com.whale.crm.integration.wecom.domain;

import java.time.Instant;

public record WecomContactSyncRun(Long id, String corpId, String runType, String status, String cursorValue,
                                  int receivedCount, int upsertedCount, int deactivatedCount, String errorMasked,
                                  Instant startedAt, Instant finishedAt) {
}
