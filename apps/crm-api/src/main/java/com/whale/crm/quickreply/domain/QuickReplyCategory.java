package com.whale.crm.quickreply.domain;

import java.time.Instant;

public record QuickReplyCategory(Long id, long tenantId, Long parentId, String name, String code,
                                 String scopeType, Long scopeId, int sortNo, String status, Long createdBy,
                                 Instant createdAt, Instant updatedAt, long version) {
}
