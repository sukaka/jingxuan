package com.whale.crm.notification.api;

import com.whale.crm.notification.api.dto.NotificationDeliveryResponse;
import com.whale.crm.notification.api.dto.RetryNotificationDeliveryRequest;
import com.whale.crm.notification.application.NotificationApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/notification-deliveries")
public class NotificationDeliveryController {
    private final NotificationApplicationService service;

    public NotificationDeliveryController(NotificationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('notification:delivery:read')")
    public ApiResponse<PageResponse<NotificationDeliveryResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long notificationId,
            @RequestParam(required = false) Long recipientUserId,
            @RequestParam(required = false) @Pattern(regexp = "IN_APP|WECOM_APP") String channel,
            @RequestParam(required = false)
            @Pattern(regexp = "REQUESTED|PENDING|SENDING|RETRY_WAIT|SUCCEEDED|MOCKED|DISABLED|FAILED|UNKNOWN") String status,
            HttpServletRequest request) {
        return ApiResponse.ok(service.searchDeliveries(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), notificationId, recipientUserId, channel, status,
                page, size), requestId(request));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('notification:delivery:retry')")
    public ApiResponse<NotificationDeliveryResponse> retry(
            @PathVariable long id, @Valid @RequestBody RetryNotificationDeliveryRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128)
            @Pattern(regexp = ".*\\S.*", message = "请求标识不能为空白") String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.retry(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
