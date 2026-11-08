package com.whale.crm.refund.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.refund.api.dto.CreateRefundRequest;
import com.whale.crm.refund.api.dto.ExecuteRefundRequest;
import com.whale.crm.refund.api.dto.ManualRefundSuccessRequest;
import com.whale.crm.refund.api.dto.RefundDetail;
import com.whale.crm.refund.api.dto.ReviewRefundRequest;
import com.whale.crm.refund.application.RefundApplicationService;
import com.whale.crm.refund.domain.Refund;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@RequestMapping("/api/v1/refunds")
public class RefundController {
    private final RefundApplicationService service;

    public RefundController(RefundApplicationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('refund:write')")
    public ResponseEntity<ApiResponse<RefundDetail>> create(
            @Valid @RequestBody CreateRefundRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        RefundDetail created = service.create(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('refund:read')")
    public ApiResponse<PageResponse<Refund>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @Size(max = 32) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(service.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                orderId, customerId, status, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('refund:read')")
    public ApiResponse<RefundDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('refund:approve')")
    public ApiResponse<RefundDetail> review(
            @PathVariable long id, @Valid @RequestBody ReviewRefundRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/executions")
    @PreAuthorize("hasAuthority('refund:execute')")
    public ApiResponse<RefundDetail> execute(
            @PathVariable long id, @Valid @RequestBody ExecuteRefundRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.execute(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @PostMapping("/{id}/success")
    @PreAuthorize("hasAuthority('refund:execute')")
    public ApiResponse<RefundDetail> succeed(
            @PathVariable long id, @Valid @RequestBody ManualRefundSuccessRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.succeedManually(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingRequestHeader(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(),
                "退款写请求必须提供幂等键", null, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
