package com.whale.crm.annualreport.domain;

import java.time.Instant;

/**
 * C 端统一入口访问埋点会话（服务于纯有赞历史快照用户）。
 */
public record H5UnifiedVisitSession(
        Long id,
        long tenantId,
        String shopId,
        String yzOpenId,
        Long customerId,
        String displayNameSnapshot,
        int reportYear,
        String sessionKey,
        String entryChannel,
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
        String clientDevice,
        Instant createdAt,
        Instant updatedAt
) {
}
