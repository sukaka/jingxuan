package com.whale.crm.integration.wecom.api;

import com.whale.crm.integration.wecom.api.dto.WecomSyncPolicyRequest;
import com.whale.crm.integration.wecom.application.WecomSyncPolicyService;
import com.whale.crm.integration.wecom.domain.WecomSyncPolicy;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/wecom/sync-policy")
public class WecomSyncPolicyController extends WecomBaseController {
    private final WecomSyncPolicyService service;

    public WecomSyncPolicyController(WecomSyncPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('wecom:read')")
    public ApiResponse<WecomSyncPolicy> get(@RequestParam String corpId, HttpServletRequest request) {
        return ApiResponse.ok(service.get(SecuritySupport.requireCurrentUser().tenantId(), corpId), requestId(request));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomSyncPolicy> save(@RequestParam String corpId,
            @Valid @RequestBody WecomSyncPolicyRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.save(SecuritySupport.requireCurrentUser(), corpId, body, key), requestId(request));
    }
}
