package com.whale.crm.customer.batchtag.api;

import com.whale.crm.customer.batchtag.api.dto.BatchTagJobActionRequest;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobDetailResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagJobResponse;
import com.whale.crm.customer.batchtag.api.dto.BatchTagPreviewResponse;
import com.whale.crm.customer.batchtag.api.dto.CreateBatchTagJobRequest;
import com.whale.crm.customer.batchtag.api.dto.PreviewBatchTagJobRequest;
import com.whale.crm.customer.batchtag.application.BatchTagJobApplicationService;
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
@RequestMapping("/api/v1/batch-tag-jobs")
public class BatchTagJobController {
    private final BatchTagJobApplicationService service;

    public BatchTagJobController(BatchTagJobApplicationService service) {
        this.service = service;
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<BatchTagPreviewResponse> preview(@Valid @RequestBody PreviewBatchTagJobRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.preview(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                body, key), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<BatchTagJobDetailResponse>> create(
            @Valid @RequestBody CreateBatchTagJobRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        BatchTagJobDetailResponse created = service.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<BatchTagJobResponse>> search(
            @RequestParam(required = false) @Pattern(regexp = "PENDING|RUNNING|SUCCEEDED|PARTIAL_FAILED|FAILED")
            String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                status, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<BatchTagJobDetailResponse> detail(@PathVariable long id,
            @RequestParam(defaultValue = "1") @Min(1) int itemPage,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int itemSize,
            HttpServletRequest request) {
        return ApiResponse.ok(service.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, itemPage, itemSize), requestId(request));
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<BatchTagJobDetailResponse> execute(@PathVariable long id,
            @Valid @RequestBody BatchTagJobActionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.execute(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }

    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<BatchTagJobDetailResponse> retry(@PathVariable long id,
            @Valid @RequestBody BatchTagJobActionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.retry(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, key), requestId(request));
    }

    @PostMapping("/{id}/rollback")
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<BatchTagJobDetailResponse>> rollback(@PathVariable long id,
            @Valid @RequestBody BatchTagJobActionRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        BatchTagJobDetailResponse rollback = service.rollback(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rollback, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
