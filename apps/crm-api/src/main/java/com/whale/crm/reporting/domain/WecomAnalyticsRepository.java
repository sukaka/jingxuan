package com.whale.crm.reporting.domain;

import java.time.Instant;

public interface WecomAnalyticsRepository {
    QuickReplyFacts quickReplyFacts(long tenantId, Instant fromUtc, Instant toUtcExclusive, String scopeType,
                                    java.util.Set<Long> teamIds, long selfId, Long employeeId);

    AiSuggestionFacts aiSuggestionFacts(long tenantId, Instant fromUtc, Instant toUtcExclusive, String scopeType,
                                       java.util.Set<Long> teamIds, long selfId, Long employeeId);

    AiUsageFacts aiUsageFacts(long tenantId, Instant fromUtc, Instant toUtcExclusive, String scopeType,
                              java.util.Set<Long> teamIds, long selfId, Long employeeId);

    AppointmentFacts appointmentFacts(long tenantId, Instant fromUtc, Instant toUtcExclusive, String scopeType,
                                      java.util.Set<Long> teamIds, long selfId, Long employeeId);

    PaymentFacts paymentFacts(long tenantId, Instant fromUtc, Instant toUtcExclusive, String scopeType,
                              java.util.Set<Long> teamIds, long selfId, Long employeeId);

    record QuickReplyFacts(long usageCount, long copiedCount, long sdkSuccessCount, long sdkFailedCount,
                           long sdkUnknownCount, long editedCount, long aiLinkedUsageCount) {
    }

    record AiSuggestionFacts(long generatedCount, long acceptedCount, long rejectedCount,
                             long pendingCount, long editedAcceptedCount, long mockGeneratedCount) {
    }

    record AiUsageFacts(long copiedCount, long sdkSuccessCount, long sdkFailedCount, long sdkUnknownCount) {
    }

    record AppointmentFacts(long confirmedCount, long checkedInCount) {
    }

    record PaymentFacts(long paidOrderCount, long paidCustomerCount, long repeatCustomerCount) {
    }
}
