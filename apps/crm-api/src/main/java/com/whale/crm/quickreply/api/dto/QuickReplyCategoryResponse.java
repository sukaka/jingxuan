package com.whale.crm.quickreply.api.dto;

import com.whale.crm.quickreply.domain.QuickReplyCategory;

import java.time.Instant;

public record QuickReplyCategoryResponse(long id, Long parentId, String name, String code, String scopeType,
                                         Long scopeId, int sortNo, String status, Long createdBy,
                                         Instant createdAt, Instant updatedAt, long version) {
    public static QuickReplyCategoryResponse from(QuickReplyCategory category) {
        return new QuickReplyCategoryResponse(category.id(), category.parentId(), category.name(), category.code(),
                category.scopeType(), category.scopeId(), category.sortNo(), category.status(), category.createdBy(),
                category.createdAt(), category.updatedAt(), category.version());
    }
}
