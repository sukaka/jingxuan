package com.whale.crm.integration.wecom.api;

import com.whale.crm.integration.wecom.api.dto.WecomContentView;
import com.whale.crm.integration.wecom.api.dto.WecomLinkRequest;
import com.whale.crm.integration.wecom.api.dto.WecomMessageView;
import com.whale.crm.integration.wecom.api.dto.WecomMonitorView;
import com.whale.crm.integration.wecom.api.dto.WecomSyncRequest;
import com.whale.crm.integration.wecom.api.dto.WecomSyncResponse;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.application.WecomManagementService;
import com.whale.crm.integration.wecom.domain.WecomContact;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/integrations/wecom")
public class WecomController extends WecomBaseController {
    private final WecomArchiveService archiveService;
    private final WecomManagementService managementService;

    public WecomController(WecomArchiveService archiveService, WecomManagementService managementService) {
        this.archiveService = archiveService;
        this.managementService = managementService;
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomSyncResponse> sync(@Valid @RequestBody WecomSyncRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(archiveService.sync(SecuritySupport.requireCurrentUser(), body), requestId(request));
    }

    @GetMapping("/contacts")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<List<WecomContact>> contacts(HttpServletRequest request) {
        return ApiResponse.ok(managementService.contacts(SecuritySupport.requireCurrentUser().tenantId(),
                SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @PostMapping("/contacts/{id}/link")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomContact> link(@PathVariable long id, @Valid @RequestBody WecomLinkRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.link(user.tenantId(), user, id, body), requestId(request));
    }

    @GetMapping("/messages")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<PageResponse<WecomMessageView>> messages(
            @RequestParam(required = false) Long customerId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.messages(user.tenantId(), user, customerId, page, size), requestId(request));
    }

    @GetMapping("/messages/{id}/content")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<WecomContentView> content(@PathVariable long id, HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.content(user.tenantId(), user, id), requestId(request));
    }

    @GetMapping("/monitor")
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<WecomMonitorView> monitor(@RequestParam String corpId, HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.monitor(user.tenantId(), user, corpId), requestId(request));
    }
}
