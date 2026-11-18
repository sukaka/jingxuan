package com.whale.crm.quickreply.api.dto;

import com.whale.crm.quickreply.domain.QuickReplyTemplate;

import java.time.Instant;

public record QuickReplyTemplateResponse(long id, long categoryId, String name, String code, String messageType,
                                         String content, String scene, String scopeType, Long scopeId,
                                         boolean allowEdit, boolean requirePreview, boolean aiEnabled, boolean featured,
                                         String status, int sortNo, Long createdBy, Instant createdAt,
                                         Instant updatedAt, long version, boolean favorite, Instant lastUsedAt,
                                         long usageCount) {
    public static QuickReplyTemplateResponse from(QuickReplyTemplate template) {
        return from(template, false, null, 0L);
    }

    public static QuickReplyTemplateResponse from(QuickReplyTemplate template, boolean favorite,
                                                  Instant lastUsedAt, long usageCount) {
        return new QuickReplyTemplateResponse(template.id(), template.categoryId(), template.name(), template.code(),
                template.messageType(), template.content(), template.scene(), template.scopeType(), template.scopeId(),
                template.allowEdit(), template.requirePreview(), template.aiEnabled(), template.featured(),
                template.status(), template.sortNo(), template.createdBy(), template.createdAt(), template.updatedAt(),
                template.version(), favorite, lastUsedAt, usageCount);
    }
}
