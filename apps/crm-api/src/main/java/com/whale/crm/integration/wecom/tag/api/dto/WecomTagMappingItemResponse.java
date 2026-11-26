package com.whale.crm.integration.wecom.tag.api.dto;

import java.time.Instant;

/**
 * 单条 CRM ↔ 企微企业标签映射明细。无映射记录时 syncStatus 为 UNMAPPED。
 */
public record WecomTagMappingItemResponse(
        String crmTagCode,
        String crmTagLabel,
        String crmTagStatus,
        String wecomGroupId,
        String wecomGroupName,
        String wecomTagId,
        String wecomTagName,
        String syncStatus,
        Instant lastSyncedAt) {
}
