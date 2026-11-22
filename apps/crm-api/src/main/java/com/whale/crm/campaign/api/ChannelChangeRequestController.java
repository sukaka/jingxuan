package com.whale.crm.campaign.api;

import com.whale.crm.campaign.api.dto.ChannelFinancialChangeRequestResponse;
import com.whale.crm.campaign.api.dto.ReviewChannelFinancialChangeRequest;
import com.whale.crm.campaign.application.CampaignApplicationService;
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
@RequestMapping("/api/v1/channel-change-requests")
public class ChannelChangeRequestController {
    private final CampaignApplicationService service;

    public ChannelChangeRequestController(CampaignApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('channel:read', 'channel:approve_finance')")
    public ApiResponse<PageResponse<ChannelFinancialChangeRequestResponse>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long channelId,
            @RequestParam(required = false) @Size(max = 32) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(service.allChannelFinancialChangeRequests(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), channelId, status, page, size), requestId(request));
    }

    @PostMapping("/{reqId}/review")
    @PreAuthorize("hasAuthority('channel:approve_finance')")
    public ApiResponse<ChannelFinancialChangeRequestResponse> review(
            @PathVariable long reqId,
            @Valid @RequestBody ReviewChannelFinancialChangeRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(service.reviewFinancialChangeRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), reqId, body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
