package com.whale.crm.customer.tier.api;

import com.whale.crm.customer.tier.api.dto.CustomerTierAdjustmentRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierHistoryResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierRecalculateRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierViewResponse;
import com.whale.crm.customer.tier.application.CustomerTierCustomerService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
public class CustomerTierController {
    private final CustomerTierCustomerService service;

    public CustomerTierController(CustomerTierCustomerService service) {
        this.service = service;
    }

    @GetMapping("/tier")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerTierViewResponse> view(@PathVariable long customerId, HttpServletRequest request) {
        return ApiResponse.ok(service.view(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId), requestId(request));
    }

    @PostMapping("/tier-recalculations")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerTierEvaluationResponse> recalculate(
            @PathVariable long customerId, @Valid @RequestBody CustomerTierRecalculateRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.recalculate(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/tier-adjustments")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerTierViewResponse> adjust(
            @PathVariable long customerId, @Valid @RequestBody CustomerTierAdjustmentRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.adjust(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, body, idempotencyKey), requestId(request));
    }

    @GetMapping("/tier-history")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<CustomerTierHistoryResponse>> history(
            @PathVariable long customerId, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.history(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, limit), requestId(request));
    }

    @GetMapping("/tier-metric-snapshots")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<CustomerTierEvaluationResponse>> snapshots(
            @PathVariable long customerId, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.snapshots(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, limit), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
