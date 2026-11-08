package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.MergeReversalActionRequest;
import com.whale.crm.customer.api.dto.MergeReversalResponse;
import com.whale.crm.customer.application.MergeReversalApplicationService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
import com.whale.crm.platform.web.RequestIdFilter;

@Validated
@RestController
@RequestMapping("/api/v1")
public class MergeReversalController {
    private final MergeReversalApplicationService reversalService;

    public MergeReversalController(MergeReversalApplicationService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping("/customer-merge-candidates/{id}/reversal-requests")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeReversalResponse> request(@PathVariable long id,
            @Valid @RequestBody MergeReversalActionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(reversalService.request(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @GetMapping("/customer-merge-reversals")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MergeReversalResponse> get(@RequestParam long mergeCaseId, HttpServletRequest request) {
        return ApiResponse.ok(reversalService.get(SecuritySupport.requireCurrentUser(), mergeCaseId), requestId(request));
    }

    @PostMapping("/customer-merge-reversals/{id}/approve")
    @PreAuthorize("hasAuthority('customer:merge_reverse')")
    public ApiResponse<MergeReversalResponse> approve(@PathVariable long id,
            @Valid @RequestBody MergeReversalActionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(reversalService.approve(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @PostMapping("/customer-merge-reversals/{id}/complete")
    @PreAuthorize("hasAuthority('customer:merge_reverse')")
    public ApiResponse<MergeReversalResponse> complete(@PathVariable long id,
            @Valid @RequestBody MergeReversalActionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(reversalService.complete(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    @PostMapping("/customer-merge-reversals/{id}/reject")
    @PreAuthorize("hasAuthority('customer:merge_reverse')")
    public ApiResponse<MergeReversalResponse> reject(@PathVariable long id,
            @Valid @RequestBody MergeReversalActionRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(reversalService.reject(SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String value ? value : null;
    }
}
