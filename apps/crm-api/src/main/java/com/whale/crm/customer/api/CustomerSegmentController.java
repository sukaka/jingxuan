package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateCustomerSegmentRequest;
import com.whale.crm.customer.api.dto.CreateSegmentOperationBatchRequest;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerSegmentBatchResponse;
import com.whale.crm.customer.api.dto.CustomerSegmentResponse;
import com.whale.crm.customer.api.dto.PreviewCustomerSegmentRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerSegmentRequest;
import com.whale.crm.customer.application.CustomerSegmentApplicationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/customer-segments")
public class CustomerSegmentController {
    private final CustomerSegmentApplicationService segmentService;

    public CustomerSegmentController(CustomerSegmentApplicationService segmentService) {
        this.segmentService = segmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerSegmentResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword, HttpServletRequest request) {
        return ApiResponse.ok(segmentService.search(TenantContext.currentTenantId(), keyword, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<CustomerSegmentResponse>> create(
            @Valid @RequestBody CreateCustomerSegmentRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        CustomerSegmentResponse created = segmentService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerListItemResponse>> preview(
            @Valid @RequestBody PreviewCustomerSegmentRequest body, HttpServletRequest request) {
        return ApiResponse.ok(segmentService.preview(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerSegmentResponse> get(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(segmentService.get(TenantContext.currentTenantId(), id), requestId(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerSegmentResponse> update(@PathVariable long id,
            @Valid @RequestBody UpdateCustomerSegmentRequest body, HttpServletRequest request) {
        return ApiResponse.ok(segmentService.update(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long version, HttpServletRequest request) {
        segmentService.delete(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, version);
        return ApiResponse.ok(null, requestId(request));
    }

    @GetMapping("/{id}/members")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerListItemResponse>> members(
            @PathVariable long id, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, HttpServletRequest request) {
        return ApiResponse.ok(segmentService.members(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, page, size), requestId(request));
    }

    @PostMapping("/{id}/operation-batches")
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<CustomerSegmentBatchResponse>> createOperationBatch(
            @PathVariable long id, @Valid @RequestBody CreateSegmentOperationBatchRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        CustomerSegmentBatchResponse created = segmentService.createOperationBatch(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
