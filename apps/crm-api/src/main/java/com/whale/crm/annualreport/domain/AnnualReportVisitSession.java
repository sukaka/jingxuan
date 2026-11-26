package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * 客户年终 H5 访问会话聚合实体。
 */
public record AnnualReportVisitSession(
        Long id,
        long tenantId,
        long reportId,
        long customerId,
        long ownerStaffId,
        Long teamId,
        int reportYear,
        String sessionKey,
        Instant firstOpenAt,
        Instant lastActiveAt,
        long visibleDurationMs,
        int maxSceneReached,
        int viewedSceneCount,
        String viewedScenesJson,
        String sceneDurationsJson,
        boolean ignitedLamp,
        int openedOrderCount,
        boolean toggledPrivacy,
        boolean generatedPoster,
        boolean clickedBookButler,
        Long bookingTaskId,
        String clientDevice,
        Instant createdAt,
        Instant updatedAt
) {
}
