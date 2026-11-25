package com.whale.crm.notification.api;

import com.whale.crm.notification.api.dto.MarkNotificationReadRequest;
import com.whale.crm.notification.api.dto.NotificationResponse;
import com.whale.crm.notification.api.dto.UnreadCountResponse;
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
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationApplicationService service;

    public NotificationController(NotificationApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('notification:read')")
    public ApiResponse<PageResponse<NotificationResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) @Size(max = 64)
            @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "通知类型格式无效") String notificationType,
            HttpServletRequest request) {
        return ApiResponse.ok(service.searchOwn(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                unreadOnly, notificationType, page, size), requestId(request));
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasAuthority('notification:read')")
    public ApiResponse<UnreadCountResponse> unreadCount(HttpServletRequest request) {
        return ApiResponse.ok(service.unreadCount(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasAuthority('notification:read')")
    public ApiResponse<NotificationResponse> markRead(
            @PathVariable long id, @Valid @RequestBody(required = true) MarkNotificationReadRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128)
            @Pattern(regexp = ".*\\S.*", message = "请求标识不能为空白") String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.markRead(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
