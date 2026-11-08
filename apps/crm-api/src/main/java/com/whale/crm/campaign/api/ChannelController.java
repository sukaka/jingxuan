package com.whale.crm.campaign.api;

import com.whale.crm.campaign.api.dto.CreateChannelRequest;
import com.whale.crm.campaign.api.dto.UpdateChannelRequest;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.PartnerChannel;
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
@RequestMapping("/api/v1/channels")
public class ChannelController {
    private final CampaignApplicationService service;

    public ChannelController(CampaignApplicationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('channel:read')")
    public ApiResponse<PageResponse<PartnerChannel>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) Long businessOwnerUserId,
            HttpServletRequest request) {
        return ApiResponse.ok(service.channels(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                keyword, status, businessOwnerUserId, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('channel:write')")
    public ResponseEntity<ApiResponse<PartnerChannel>> create(@Valid @RequestBody CreateChannelRequest body,
                                                              HttpServletRequest request) {
        PartnerChannel created = service.createChannel(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('channel:write')")
    public ApiResponse<PartnerChannel> update(@PathVariable long id, @Valid @RequestBody UpdateChannelRequest body,
                                              HttpServletRequest request) {
        return ApiResponse.ok(service.updateChannel(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
