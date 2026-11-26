package com.whale.crm.integration.wecom.tag.api.dto;

import java.time.Instant;

/**
 * CRM → 企微客户标签推送任务队列项响应（externalUserid 严禁明文出参，仅返回脱敏值）。
 */
public record WecomTagPushTaskResponse(
        Long id,
        String corpId,
        long customerId,
        String externalUseridMasked,
        String wecomUserid,
        int addTagCount,
        int removeTagCount,
        String status,
        int attempts,
        int maxAttempts,
        String triggerSource,
        String errorMasked,
        Instant nextRetryAt,
        Instant updatedAt) {
}
