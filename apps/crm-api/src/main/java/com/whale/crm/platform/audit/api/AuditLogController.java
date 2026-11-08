package com.whale.crm.platform.audit.api;

import com.whale.crm.platform.audit.api.dto.AuditLogResponse;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询（audit:read）。审计只追加、不可编辑，本控制器不提供任何写接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditService auditService;

    public AuditLogController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public ApiResponse<PageResponse<AuditLogResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) @Size(max = 96) String actionCode,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_]{1,64}", message = "targetType 格式无效")
            String targetType,
            HttpServletRequest request) {
        PageResponse<AuditLogResponse> result = auditService.search(
                TenantContext.currentTenantId(), actorId, actionCode, targetType, page, size);
        return ApiResponse.ok(result, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
