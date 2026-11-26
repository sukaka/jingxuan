package com.whale.crm.integration.wecom.tag.domain;

import java.time.Instant;

/**
 * CRM 客户标签与企微企业标签映射实体（crm_wecom_tag_mapping）。
 */
public record WecomTagMapping(
        Long id,
        long tenantId,
        String corpId,
        String crmTagCode,
        String wecomGroupId,
        String wecomGroupName,
        String wecomTagId,
        String wecomTagName,
        String syncStatus,
        Instant lastSyncedAt,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_UNLINKED = "UNLINKED";
    public static final String STATUS_DISABLED = "DISABLED";
}
