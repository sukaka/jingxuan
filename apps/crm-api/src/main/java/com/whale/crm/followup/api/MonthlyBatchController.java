package com.whale.crm.followup.api;

import com.whale.crm.followup.api.dto.CreateMonthlyBatchRequest;
import com.whale.crm.followup.api.dto.MonthlyBatchActionRequest;
import com.whale.crm.followup.api.dto.MonthlyBatchPreviewResponse;
import com.whale.crm.followup.api.dto.MonthlyBatchResponse;
import com.whale.crm.followup.application.MonthlyBatchService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/followup-automation/batches")
public class MonthlyBatchController {
    private final MonthlyBatchService service;

    public MonthlyBatchController(MonthlyBatchService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('followup:read')")
    public ApiResponse<MonthlyBatchPreviewResponse> preview(
            @RequestParam @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String orderMonth,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.preview(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                orderMonth, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('followup:write')")
    public ResponseEntity<ApiResponse<MonthlyBatchResponse>> create(
            @Valid @RequestBody CreateMonthlyBatchRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                service.create(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                        body, idempotencyKey), requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('followup:read')")
    public ApiResponse<MonthlyBatchResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('followup:write')")
    public ApiResponse<MonthlyBatchResponse> execute(
            @PathVariable long id, @Valid @RequestBody MonthlyBatchActionRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.ok(service.execute(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('followup:write')")
    public ApiResponse<MonthlyBatchResponse> retry(
            @PathVariable long id, @Valid @RequestBody MonthlyBatchActionRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.ok(service.retry(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
