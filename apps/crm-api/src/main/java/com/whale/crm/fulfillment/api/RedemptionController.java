package com.whale.crm.fulfillment.api;

import com.whale.crm.fulfillment.api.dto.CreateReversalRequest;
import com.whale.crm.fulfillment.api.dto.RedemptionDetail;
import com.whale.crm.fulfillment.api.dto.ReversalDetail;
import com.whale.crm.fulfillment.application.RedemptionApplicationService;
import com.whale.crm.fulfillment.domain.Redemption;
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
@RequestMapping("/api/v1/redemptions")
public class RedemptionController extends BaseFulfillmentController {
    private final RedemptionApplicationService redemptionService;

    public RedemptionController(RedemptionApplicationService redemptionService) {
        this.redemptionService = redemptionService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('redemption:read')")
    public ApiResponse<PageResponse<Redemption>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long institutionId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(redemptionService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, institutionId, projectId, status, page, size), requestId(request));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('redemption:read')")
    public ApiResponse<RedemptionDetail> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(redemptionService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/reversal-requests")
    @PreAuthorize("hasAuthority('redemption:reverse')")
    public ApiResponse<ReversalDetail> requestReversal(@PathVariable long id, @Valid @RequestBody CreateReversalRequest body,
                                                       @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
                                                       HttpServletRequest request) {
        return ApiResponse.ok(redemptionService.requestReversal(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, key), requestId(request));
    }
}
