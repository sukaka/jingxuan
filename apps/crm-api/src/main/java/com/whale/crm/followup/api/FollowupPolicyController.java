package com.whale.crm.followup.api;

import com.whale.crm.followup.api.dto.FollowupPolicyResponse;
import com.whale.crm.followup.api.dto.UpdateFollowupPolicyRequest;
import com.whale.crm.followup.application.FollowupPolicyService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/followup-automation/policy")
public class FollowupPolicyController {
    private final FollowupPolicyService service;

    public FollowupPolicyController(FollowupPolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('followup:read')")
    public ApiResponse<FollowupPolicyResponse> get(HttpServletRequest request) {
        return ApiResponse.ok(service.get(TenantContext.currentTenantId()), requestId(request));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('followup:policy:manage')")
    public ApiResponse<FollowupPolicyResponse> update(
            @Valid @RequestBody UpdateFollowupPolicyRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey, HttpServletRequest request) {
        return ApiResponse.ok(service.update(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                body, idempotencyKey), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
