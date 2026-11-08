package com.whale.crm.platform.audit.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 审计日志对外 DTO。before/after 为写入时保存的脱敏快照。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String actorUsername,
        String actionCode,
        String targetType,
        String targetId,
        JsonNode before,
        JsonNode after,
        String traceId,
        String clientIp,
        String reason,
        Instant createdAt) {
}
