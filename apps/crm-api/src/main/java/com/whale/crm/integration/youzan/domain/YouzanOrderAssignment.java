package com.whale.crm.integration.youzan.domain;

import java.time.Instant;

public record YouzanOrderAssignment(
        Long id,
        Long tenantId,
        String shopId,
        String externalOrderNo,
        Long latestEventId,
        String providerOrderStatus,
        String providerStatusText,
        String payWayDesc,
        Instant orderedAt,
        Instant consignedAt,
        String buyerFormNameMasked,
        String buyerFormMobileMasked,
        String tradeMemoMasked,
        String memoPerformanceOwner,
        String memoChannel,
        String memoCustomerType,
        String memoSource,
        String memoAccountName,
        String memoAccountKey,
        String memoInstitutionName,
        String memoExtraNote,
        Long assignedStewardId,
        Long assignedTeamId,
        String assignmentStatus,
        String assignmentSource,
        String assignmentReason,
        Long assignedBy,
        Instant assignedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version) {
}
