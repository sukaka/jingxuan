package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateFollowupRequest;
import com.whale.crm.customer.api.dto.FollowupItemResponse;
import com.whale.crm.customer.api.dto.UpdateFollowupRequest;
import com.whale.crm.customer.application.FollowupApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日跟进（followup:read/write）。列表按客户数据范围过滤；创建者或同组主管可改。
 */
@Validated
@RestController
@RequestMapping("/api/v1/followups")
public class FollowupController {

    private final FollowupApplicationService followupService;

    public FollowupController(FollowupApplicationService followupService) {
        this.followupService = followupService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('followup:read')")
    public ApiResponse<PageResponse<FollowupItemResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            HttpServletRequest request) {
        return ApiResponse.ok(followupService.search(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('followup:write')")
    public ResponseEntity<ApiResponse<FollowupItemResponse>> create(
            @Valid @RequestBody CreateFollowupRequest body, HttpServletRequest request) {
        FollowupItemResponse created = followupService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('followup:write')")
    public ApiResponse<FollowupItemResponse> update(@PathVariable long id,
                                                    @Valid @RequestBody UpdateFollowupRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(followupService.update(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('followup:write')")
    public ApiResponse<Void> delete(@PathVariable long id, @RequestParam long version, HttpServletRequest request) {
        followupService.delete(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, version);
        return ApiResponse.ok(null, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
