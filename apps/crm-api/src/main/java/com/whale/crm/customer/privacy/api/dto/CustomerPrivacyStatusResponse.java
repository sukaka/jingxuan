package com.whale.crm.customer.privacy.api.dto;

import java.util.List;

/**
 * 客户详情页隐私信息保护状态视图（绝不返回任何隐私明文）。
 */
public record CustomerPrivacyStatusResponse(
        Long customerId,
        List<PrivacyFieldSummary> fields,
        boolean canAdminDirectReveal,
        boolean canRequestAccess,
        boolean canViewAuditLogs,
        ResolvedApproverPreview resolvedApprover,
        CustomerPrivacyRequestResponse pendingRequest,
        CustomerPrivacyRequestResponse activeGrant,
        List<Integer> allowedDurationMinutes) {
    public CustomerPrivacyStatusResponse {
        fields = fields == null ? List.of() : List.copyOf(fields);
        allowedDurationMinutes = allowedDurationMinutes == null ? List.of() : List.copyOf(allowedDurationMinutes);
    }

    public record PrivacyFieldSummary(
            String fieldCode,
            String label,
            boolean present,
            String maskedValue) {
    }

    public record ResolvedApproverPreview(
            Long approverUserId,
            String approverName,
            String approverSource) {
    }
}
