package com.whale.crm.customer.privacy.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 隐私查看申请单响应（绝不含客户隐私明文）。
 */
public record CustomerPrivacyRequestResponse(
        Long id,
        String requestNo,
        Long customerId,
        String customerNo,
        String customerName,
        Long applicantUserId,
        String applicantName,
        Long applicantTeamId,
        List<String> requestedFields,
        List<String> approvedFields,
        String reason,
        String status,
        String effectiveStatus,
        Long assignedApproverId,
        String assignedApproverName,
        String approverSource,
        Integer requestedDurationMinutes,
        Integer approvedDurationMinutes,
        Long reviewedBy,
        String reviewedByName,
        Instant reviewedAt,
        String reviewReason,
        Instant grantStartsAt,
        Instant grantExpiresAt,
        Long remainingSeconds,
        Long revokedBy,
        String revokedByName,
        Instant revokedAt,
        String revokeReason,
        Integer revealCount,
        Instant lastRevealedAt,
        Instant createdAt,
        Long version) {
    public CustomerPrivacyRequestResponse {
        requestedFields = requestedFields == null ? List.of() : List.copyOf(requestedFields);
        approvedFields = approvedFields == null ? List.of() : List.copyOf(approvedFields);
    }
}
