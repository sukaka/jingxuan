package com.whale.crm.customer.privacy.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 客户隐私揭盲审计日志对外视图（仅展示脱敏快照，绝不包含明文）。
 */
public record CustomerPrivacyAccessLogResponse(
        Long id,
        Long customerId,
        String customerNo,
        String customerName,
        Long actorUserId,
        String actorUsername,
        String actorDisplayName,
        String actorRoleSnapshot,
        String accessMode,
        Long requestId,
        String requestNo,
        List<String> revealedFields,
        Map<String, String> maskedValuesSnapshot,
        String reason,
        String clientIp,
        String userAgent,
        String traceId,
        Instant createdAt) {
    public CustomerPrivacyAccessLogResponse {
        revealedFields = revealedFields == null ? List.of() : List.copyOf(revealedFields);
        maskedValuesSnapshot = maskedValuesSnapshot == null ? Map.of() : Map.copyOf(maskedValuesSnapshot);
    }
}
