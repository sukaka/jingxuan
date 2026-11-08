package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateMergeCaseRequest;
import com.whale.crm.customer.api.dto.MergeCandidateItemResponse;
import com.whale.crm.customer.api.dto.MergeScanResultResponse;
import com.whale.crm.customer.api.dto.RejectMergeCaseRequest;
import com.whale.crm.customer.api.dto.ReviewMergeCaseRequest;
import com.whale.crm.customer.application.MergeApplicationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户查重合并（customer:merge）。扫描/登记只生成 SUGGESTED 候选；
 * review/confirm/reject 走状态机，confirm 才在同一事务迁移关联数据。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CustomerMergeController {

    private final MergeApplicationService mergeService;

    public CustomerMergeController(MergeApplicationService mergeService) {
        this.mergeService = mergeService;
    }

    @GetMapping("/customer-merge-candidates")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<PageResponse<MergeCandidateItemResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false)
            @Pattern(regexp = "SUGGESTED|REVIEWING|CONFIRMED|REJECTED", message = "status 无效") String status,
            HttpServletRequest request) {
        return ApiResponse.ok(mergeService.list(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), status, page, size), requestId(request));
    }

    @PostMapping("/customer-merge-candidates/scan")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeScanResultResponse> scan(HttpServletRequest request) {
        return ApiResponse.ok(mergeService.scan(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/customer-merges")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ResponseEntity<ApiResponse<MergeCandidateItemResponse>> create(
            @Valid @RequestBody CreateMergeCaseRequest body, HttpServletRequest request) {
        MergeCandidateItemResponse created = mergeService.createCase(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/customer-merges/{id}/review")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeCandidateItemResponse> review(@PathVariable long id,
                                                          @Valid @RequestBody ReviewMergeCaseRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(mergeService.review(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body.version(), body.reason()), requestId(request));
    }

    @PostMapping("/customer-merges/{id}/confirm")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeCandidateItemResponse> confirm(@PathVariable long id,
                                                           @Valid @RequestBody ReviewMergeCaseRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(mergeService.confirm(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body.version(), body.reason()), requestId(request));
    }

    @PostMapping("/customer-merges/{id}/reject")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeCandidateItemResponse> reject(@PathVariable long id,
                                                          @Valid @RequestBody RejectMergeCaseRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(mergeService.reject(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body.version(), body.reason()), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
