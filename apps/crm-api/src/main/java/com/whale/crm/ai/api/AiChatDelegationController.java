package com.whale.crm.ai.api;

import com.whale.crm.ai.api.dto.AiChatDelegationCustomerControlRequest;
import com.whale.crm.ai.api.dto.AiChatDelegationCustomerControlResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationEmergencyStopRequest;
import com.whale.crm.ai.api.dto.AiChatDelegationPolicyResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationRunResponse;
import com.whale.crm.ai.api.dto.AiChatDelegationStatusResponse;
import com.whale.crm.ai.api.dto.UpdateAiChatDelegationPolicyRequest;
import com.whale.crm.ai.application.AiChatDelegationSettingsService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ai/chat-delegation")
public class AiChatDelegationController {
    private final AiChatDelegationSettingsService service;
    private final com.whale.crm.ai.application.AiChatSendFenceService sendFenceService;

    public AiChatDelegationController(AiChatDelegationSettingsService service,
                                      com.whale.crm.ai.application.AiChatSendFenceService sendFenceService) {
        this.service = service;
        this.sendFenceService = sendFenceService;
    }

    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<AiChatDelegationPolicyResponse> policy(HttpServletRequest request) {
        return ApiResponse.ok(service.getPolicy(TenantContext.currentTenantId()), requestId(request));
    }

    @PutMapping("/policy")
    @PreAuthorize("hasAuthority('ai:config')")
    public ApiResponse<AiChatDelegationPolicyResponse> updatePolicy(
            @Valid @RequestBody UpdateAiChatDelegationPolicyRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(service.updatePolicy(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @PostMapping("/emergency-stop")
    @PreAuthorize("hasAuthority('ai:config')")
    public ApiResponse<AiChatDelegationPolicyResponse> emergencyStop(
            @Valid @RequestBody AiChatDelegationEmergencyStopRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        AiChatDelegationPolicyResponse response = service.emergencyStop(SecuritySupport.requireCurrentUser(), body, key);
        if (body.stopped()) {
            sendFenceService.awaitTenant(TenantContext.currentTenantId());
        }
        return ApiResponse.ok(response, requestId(request));
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<AiChatDelegationStatusResponse> status(HttpServletRequest request) {
        return ApiResponse.ok(service.status(SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<PageResponse<AiChatDelegationRunResponse>> runs(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @Pattern(regexp = "QUEUED|DEFERRED|GENERATING|READY_TO_SEND|SENDING|SENT|MOCKED|DISABLED|SKIPPED|FAILED|RETRY_GENERATE|RETRY_SEND|UNKNOWN") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(service.runs(SecuritySupport.requireCurrentUser(), customerId, status, page, size),
                requestId(request));
    }

    @GetMapping("/customers/{customerId}/control")
    @PreAuthorize("hasAuthority('ai:read')")
    public ApiResponse<AiChatDelegationCustomerControlResponse> customerControl(
            @PathVariable long customerId, HttpServletRequest request) {
        return ApiResponse.ok(service.getCustomerControl(SecuritySupport.requireCurrentUser(), customerId),
                requestId(request));
    }

    @PutMapping("/customers/{customerId}/control")
    @PreAuthorize("hasAuthority('ai:write')")
    public ApiResponse<AiChatDelegationCustomerControlResponse> setCustomerControl(
            @PathVariable long customerId, @Valid @RequestBody AiChatDelegationCustomerControlRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        AiChatDelegationCustomerControlResponse response = service.setCustomerControl(
                SecuritySupport.requireCurrentUser(), customerId, body, key);
        if (!"ACTIVE".equals(body.mode())) {
            sendFenceService.awaitCustomer(SecuritySupport.requireCurrentUser().tenantId(), customerId);
        }
        return ApiResponse.ok(response, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
