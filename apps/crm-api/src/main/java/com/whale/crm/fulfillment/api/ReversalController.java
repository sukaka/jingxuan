package com.whale.crm.fulfillment.api;

import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.api.dto.ReviewReversalRequest;
import com.whale.crm.fulfillment.application.ReversalApplicationService;
import com.whale.crm.fulfillment.domain.RedemptionReversal;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
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
@RequestMapping("/api/v1/redemption-reversals")
public class ReversalController extends BaseFulfillmentController {
    private final ReversalApplicationService reversalService;

    public ReversalController(ReversalApplicationService reversalService) {
        this.reversalService = reversalService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('redemption:read','redemption:approve')")
    public ApiResponse<PageResponse<RedemptionReversal>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(reversalService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, institutionId, status, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('redemption:read','redemption:approve')")
    public ApiResponse<ReversalDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(reversalService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('redemption:approve')")
    public ApiResponse<ReversalDetail> approve(@PathVariable long id, @Valid @RequestBody ReviewReversalRequest body,
                                               @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                               HttpServletRequest request) {
        return ApiResponse.ok(reversalService.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "approve", body, key), requestId(request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('redemption:approve')")
    public ApiResponse<ReversalDetail> reject(@PathVariable long id, @Valid @RequestBody ReviewReversalRequest body,
                                              @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                              HttpServletRequest request) {
        return ApiResponse.ok(reversalService.review(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, "reject", body, key), requestId(request));
    }
}
