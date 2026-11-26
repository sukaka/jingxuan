package com.whale.crm.annualreport.api.dto;

/**
 * C 端 H5 埋点上报回执（脱敏，不泄露内部 ID）。
 */
public record PublicAnnualTelemetryResponse(
        boolean accepted,
        long visibleDurationMs,
        int viewedSceneCount,
        int maxSceneReached
) {
}
