package com.whale.crm.trading.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.trading.application.EntitlementApplicationService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/entitlements")
public class EntitlementController {
    private final EntitlementApplicationService entitlementService;

    public EntitlementController(EntitlementApplicationService entitlementService) {
        this.entitlementService = entitlementService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('entitlement:read')")
    public ApiResponse<PageResponse<Entitlement>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(entitlementService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, status, page, size), requestId(request));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('entitlement:read')")
    public ApiResponse<com.whale.crm.trading.domain.EntitlementSummary> summary(
            @RequestParam(required = false) Long customerId, HttpServletRequest request) {
        return ApiResponse.ok(entitlementService.summary(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('entitlement:read')")
    public ApiResponse<EntitlementDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(entitlementService.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
