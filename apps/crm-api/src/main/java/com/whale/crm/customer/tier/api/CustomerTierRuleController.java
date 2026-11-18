package com.whale.crm.customer.tier.api;

import com.whale.crm.customer.tier.api.dto.CustomerTierEvaluationResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierRuleRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierRuleResponse;
import com.whale.crm.customer.tier.api.dto.CustomerTierTrialRequest;
import com.whale.crm.customer.tier.api.dto.CustomerTierVersionRequest;
import com.whale.crm.customer.tier.application.CustomerTierRuleService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/customer-tier-rules")
@PreAuthorize("hasRole('ADMIN') and hasAuthority('customer:write')")
public class CustomerTierRuleController {
    private final CustomerTierRuleService service;

    public CustomerTierRuleController(CustomerTierRuleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<CustomerTierRuleResponse>> list(HttpServletRequest request) {
        return ApiResponse.ok(service.list(TenantContext.currentTenantId()), requestId(request));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CustomerTierRuleResponse>> create(
            @Valid @RequestBody CustomerTierRuleRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        CustomerTierRuleResponse created = service.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerTierRuleResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), id), requestId(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CustomerTierRuleResponse> update(
            @PathVariable long id, @RequestParam @Min(0) long version,
            @Valid @RequestBody CustomerTierRuleRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, version, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/activation")
    public ApiResponse<CustomerTierRuleResponse> activate(
            @PathVariable long id, @Valid @RequestBody CustomerTierVersionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.activate(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body.version(), idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/retirement")
    public ApiResponse<CustomerTierRuleResponse> retire(
            @PathVariable long id, @Valid @RequestBody CustomerTierVersionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.retire(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body.version(), idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/trial")
    public ApiResponse<CustomerTierEvaluationResponse> trial(
            @PathVariable long id, @Valid @RequestBody CustomerTierTrialRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.trial(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body), requestId(request));
    }

    @GetMapping("/{id}/samples")
    public ApiResponse<List<CustomerTierEvaluationResponse>> sample(
            @PathVariable long id, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            HttpServletRequest request) {
        return ApiResponse.ok(service.sample(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, limit), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
