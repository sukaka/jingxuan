package com.whale.crm.quickreply.domain;

import java.time.Instant;

public record QuickReplyTemplate(Long id, long tenantId, long categoryId, String name, String code,
                                 String messageType, String content, String scene, String scopeType, Long scopeId,
                                 boolean allowEdit, boolean requirePreview, boolean aiEnabled, boolean featured,
                                 String status,
                                 int sortNo, Long createdBy, Instant createdAt, Instant updatedAt, long version) {
}
