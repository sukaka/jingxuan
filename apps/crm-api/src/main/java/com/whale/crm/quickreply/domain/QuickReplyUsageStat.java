package com.whale.crm.quickreply.domain;

import java.time.Instant;

public record QuickReplyUsageStat(long templateId, long totalCount, long copiedCount, long successCount,
                                  long failedCount, long unknownCount, long editedCount, long employeeCount,
                                  Instant lastUsedAt) {
}
