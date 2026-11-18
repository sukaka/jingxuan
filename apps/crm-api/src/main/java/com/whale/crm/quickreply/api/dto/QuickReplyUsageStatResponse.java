package com.whale.crm.quickreply.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record QuickReplyUsageStatResponse(long templateId, String templateName, String templateCode,
                                          long totalCount, long copiedCount, long successCount, long failedCount,
                                          long unknownCount, long editedCount, long employeeCount,
                                          BigDecimal successRate, Instant lastUsedAt) {
}
