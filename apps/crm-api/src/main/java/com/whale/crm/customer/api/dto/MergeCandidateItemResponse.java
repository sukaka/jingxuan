package com.whale.crm.customer.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 合并候选/合并单。primarySummary/secondarySummary 为脱敏摘要串；matchEvidence 为 JSON 证据。
 */
public record MergeCandidateItemResponse(
        Long id,
        Long primaryCustomerId,
        Long secondaryCustomerId,
        String primarySummary,
        String secondarySummary,
        JsonNode matchEvidence,
        String status,
        Instant createdAt,
        Long version,
        Long reversalId,
        String reversalStatus,
        String reversalWarning,
        Long reversalVersion) {
}
