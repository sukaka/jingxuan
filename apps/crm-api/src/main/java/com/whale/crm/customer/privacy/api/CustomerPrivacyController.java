package com.whale.crm.customer.privacy.api;

import com.whale.crm.customer.privacy.api.dto.ApprovePrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.CancelPrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.CreatePrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyAccessLogResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyRequestResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyRevealResponse;
import com.whale.crm.customer.privacy.api.dto.CustomerPrivacyStatusResponse;
import com.whale.crm.customer.privacy.api.dto.RejectPrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.RevealCustomerPrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.RevokePrivacyRequest;
import com.whale.crm.customer.privacy.application.CustomerPrivacyApplicationService;
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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/**
 * 客户隐私信息申请、上级审批、显式揭盲与访问审计日志接口。
 * 揭盲响应强制附加 Cache-Control: no-store 与 Pragma: no-cache，防止中间代理或浏览器缓存明文。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CustomerPrivacyController {

    private final CustomerPrivacyApplicationService privacyService;

    public CustomerPrivacyController(CustomerPrivacyApplicationService privacyService) {
        this.privacyService = privacyService;
    }

    @GetMapping("/customers/{customerId}/privacy/status")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerPrivacyStatusResponse> status(@PathVariable long customerId,
                                                             HttpServletRequest request) {
        return ApiResponse.ok(privacyService.statusForCustomer(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId), requestId(request));
    }

    @PostMapping("/customers/{customerId}/privacy/requests")
    @PreAuthorize("hasAuthority('customer:privacy:request')")
    public ResponseEntity<ApiResponse<CustomerPrivacyRequestResponse>> createRequest(
            @PathVariable long customerId,
            @Valid @RequestBody CreatePrivacyRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        CustomerPrivacyRequestResponse created = privacyService.createRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PostMapping("/customers/{customerId}/privacy/reveal")
    @PreAuthorize("hasAnyAuthority('customer:privacy:request','customer:privacy:admin_reveal')")
    public ResponseEntity<ApiResponse<CustomerPrivacyRevealResponse>> reveal(
            @PathVariable long customerId,
            @Valid @RequestBody RevealCustomerPrivacyRequest body,
            HttpServletRequest request) {
        CustomerPrivacyRevealResponse revealed = privacyService.reveal(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, body,
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().mustRevalidate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(ApiResponse.ok(revealed, requestId(request)));
    }

    @GetMapping("/customer-privacy-requests")
    @PreAuthorize("hasAnyAuthority('customer:privacy:request','customer:privacy:approve')")
    public ApiResponse<PageResponse<CustomerPrivacyRequestResponse>> listRequests(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 32) String view,
            @RequestParam(required = false) @Size(max = 32) String status,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) Long customerId,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.listRequests(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), view, status, keyword, customerId, page, size),
                requestId(request));
    }

    @PostMapping("/customer-privacy-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('customer:privacy:request')")
    public ApiResponse<CustomerPrivacyRequestResponse> cancel(
            @PathVariable long id,
            @Valid @RequestBody CancelPrivacyRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.cancelRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body.version()), requestId(request));
    }

    @PostMapping("/customer-privacy-requests/{id}/approve")
    @PreAuthorize("hasAuthority('customer:privacy:approve')")
    public ApiResponse<CustomerPrivacyRequestResponse> approve(
            @PathVariable long id,
            @Valid @RequestBody ApprovePrivacyRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.approveRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/customer-privacy-requests/{id}/reject")
    @PreAuthorize("hasAuthority('customer:privacy:approve')")
    public ApiResponse<CustomerPrivacyRequestResponse> reject(
            @PathVariable long id,
            @Valid @RequestBody RejectPrivacyRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.rejectRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/customer-privacy-requests/{id}/revoke")
    @PreAuthorize("hasAuthority('customer:privacy:approve')")
    public ApiResponse<CustomerPrivacyRequestResponse> revoke(
            @PathVariable long id,
            @Valid @RequestBody RevokePrivacyRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.revokeRequest(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @GetMapping("/customer-privacy-access-logs")
    @PreAuthorize("hasAuthority('customer:privacy:audit')")
    public ApiResponse<PageResponse<CustomerPrivacyAccessLogResponse>> listAccessLogs(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) @Size(max = 32) String accessMode,
            HttpServletRequest request) {
        return ApiResponse.ok(privacyService.listAccessLogs(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, actorUserId, accessMode, page, size),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
