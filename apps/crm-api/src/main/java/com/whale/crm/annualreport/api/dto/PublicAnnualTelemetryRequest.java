package com.whale.crm.annualreport.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * C 端 H5 公开埋点上报请求（仅上报单次会话增量与交互事件，服务端强校验与封顶）。
 */
public record PublicAnnualTelemetryRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$") String sessionKey,
        @NotBlank @Pattern(regexp = "^(SESSION_OPEN|SCENE_CHANGE|HEARTBEAT|VISIBILITY_HIDE|PAGE_HIDE|IGNITE_LAMP|VIEW_ORDER|TOGGLE_PRIVACY|GENERATE_POSTER|BOOKING_INTENT)$") String eventType,
        @NotNull @Min(1) @Max(6) Integer sceneIndex,
        @Min(0) @Max(60000) Integer deltaDurationMs,
        @Pattern(regexp = "^(MOBILE|DESKTOP|WECHAT|UNKNOWN)$") String clientDevice,
        @Size(max = 200) String detail
) {
}
