package com.whale.crm.annualreport.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 单份年度报告的访问会话明细响应。
 */
public record AnnualReportSessionResponse(
        long id,
        long reportId,
        String sessionKey,
        Instant firstOpenAt,
        Instant lastActiveAt,
        long visibleDurationMs,
        int maxSceneReached,
        int viewedSceneCount,
        List<Integer> viewedScenes,
        Map<String, Long> sceneDurationsMs,
        boolean ignitedLamp,
        int openedOrderCount,
        boolean toggledPrivacy,
        boolean generatedPoster,
        boolean clickedBookButler,
        Long bookingTaskId,
        String clientDevice
) {
    public AnnualReportSessionResponse {
        viewedScenes = viewedScenes == null ? List.of() : List.copyOf(viewedScenes);
        sceneDurationsMs = sceneDurationsMs == null ? Map.of() : Map.copyOf(sceneDurationsMs);
    }
}
