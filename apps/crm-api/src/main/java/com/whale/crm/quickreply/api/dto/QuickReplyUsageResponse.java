package com.whale.crm.quickreply.api.dto;

import com.whale.crm.quickreply.domain.QuickReplyUsage;

import java.time.Instant;

public record QuickReplyUsageResponse(long id, long templateId, long employeeId, long customerId,
                                      String externalUserid, String scene, String originalContent,
                                      String finalContent, boolean edited, String sendStatus,
                                      String clientResult, Long aiSuggestionId, Instant sendTime, Instant createdAt) {
    public static QuickReplyUsageResponse from(QuickReplyUsage usage) {
        return new QuickReplyUsageResponse(usage.id(), usage.templateId(), usage.employeeId(), usage.customerId(),
                usage.externalUserid(), usage.scene(), usage.originalContent(), usage.finalContent(), usage.edited(),
                usage.sendStatus(), usage.clientResult(), usage.aiSuggestionId(), usage.sendTime(), usage.createdAt());
    }
}
