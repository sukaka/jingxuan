package com.whale.crm.campaign.api;

import com.whale.crm.campaign.api.dto.CreateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.CreateCampaignRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignBatchRequest;
import com.whale.crm.campaign.api.dto.UpdateCampaignRequest;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.Campaign;
import com.whale.crm.campaign.domain.CampaignBatch;
import com.whale.crm.campaign.domain.CampaignView;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    private final CampaignApplicationService service;

    public CampaignController(CampaignApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('campaign:read')")
    public ApiResponse<PageResponse<Campaign>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) @Size(max = 32) String campaignType,
            HttpServletRequest request) {
        return ApiResponse.ok(service.campaigns(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                keyword, status, campaignType, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('campaign:write')")
    public ResponseEntity<ApiResponse<Campaign>> create(@Valid @RequestBody CreateCampaignRequest body,
                                                        HttpServletRequest request) {
        Campaign created = service.createCampaign(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('campaign:read')")
    public ApiResponse<CampaignView> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(service.campaign(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ApiResponse<Campaign> update(@PathVariable long id, @Valid @RequestBody UpdateCampaignRequest body,
                                        HttpServletRequest request) {
        return ApiResponse.ok(service.updateCampaign(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/batches")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ResponseEntity<ApiResponse<CampaignBatch>> createBatch(@PathVariable long id,
            @Valid @RequestBody CreateCampaignBatchRequest body, HttpServletRequest request) {
        CampaignBatch created = service.createBatch(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}/batches/{batchId}")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ApiResponse<CampaignBatch> updateBatch(@PathVariable long id, @PathVariable long batchId,
            @Valid @RequestBody UpdateCampaignBatchRequest body, HttpServletRequest request) {
        return ApiResponse.ok(service.updateBatch(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, batchId, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
