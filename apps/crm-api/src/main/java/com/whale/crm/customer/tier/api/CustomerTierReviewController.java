package com.whale.crm.customer.tier.api;

import com.whale.crm.customer.tier.api.dto.CustomerTierReviewRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierReviewResponse;
import com.whale.crm.customer.tier.application.CustomerTierReviewService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/customer-tier-reviews")
public class CustomerTierReviewController {
    private final CustomerTierReviewService service;

    public CustomerTierReviewController(CustomerTierReviewService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerTierReviewResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.list(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                status, page, size), requestId(request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerTierReviewResponse> approve(
            @PathVariable long id, @Valid @RequestBody CustomerTierReviewRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.approve(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerTierReviewResponse> reject(
            @PathVariable long id, @Valid @RequestBody CustomerTierReviewRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.reject(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
