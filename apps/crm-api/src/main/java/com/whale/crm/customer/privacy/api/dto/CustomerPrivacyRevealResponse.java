package com.whale.crm.customer.privacy.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 显式按钮揭盲响应（仅通过 POST /privacy/reveal 返回，强制 Cache-Control: no-store）。
 */
public record CustomerPrivacyRevealResponse(
        Long customerId,
        String accessMode,
        Long requestId,
        String requestNo,
        List<String> revealedFields,
        Map<String, String> values,
        Instant revealedAt,
        Instant grantExpiresAt,
        int autoHideSeconds,
        Long accessLogId) {
    public CustomerPrivacyRevealResponse {
        revealedFields = revealedFields == null ? List.of() : List.copyOf(revealedFields);
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
