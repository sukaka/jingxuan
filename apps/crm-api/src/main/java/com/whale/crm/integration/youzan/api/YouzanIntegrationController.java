package com.whale.crm.integration.youzan.api;

import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/integrations/youzan")
public class YouzanIntegrationController extends YouzanBaseController {
    private final YouzanManagementService managementService;

    public YouzanIntegrationController(YouzanManagementService managementService) {
        this.managementService = managementService;
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResponse<YouzanOrderView>> orders(
            @RequestParam String shopId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.orders(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                shopId, page, size), requestId(request));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<YouzanOrderView> order(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(managementService.order(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id),
                requestId(request));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanSyncResponse> sync(@Valid @RequestBody YouzanSyncRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.sync(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @PostMapping("/reconcile")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanSyncResponse> reconcile(@Valid @RequestBody YouzanSyncRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.reconcile(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @GetMapping("/reconcile-report")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<YouzanReconcileReport> report(@RequestParam String shopId, HttpServletRequest request) {
        return ApiResponse.ok(managementService.report(TenantContext.currentTenantId(), shopId), requestId(request));
    }

    @GetMapping("/sync-issues")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResponse<YouzanSyncIssue>> issues(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.issues(TenantContext.currentTenantId(), status, page, size), requestId(request));
    }

    @PostMapping("/sync-issues/{id}/retry")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ApiResponse<YouzanEventResponse> retry(@PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.retryIssue(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @GetMapping("/sku-mappings")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<List<YouzanSkuMapping>> skuMappings(@RequestParam String shopId, HttpServletRequest request) {
        return ApiResponse.ok(managementService.skuMappings(TenantContext.currentTenantId(), shopId), requestId(request));
    }

    @PostMapping("/sku-mappings")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ApiResponse<YouzanSkuMapping> saveSkuMapping(@Valid @RequestBody YouzanSkuMappingRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.upsertSkuMapping(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @GetMapping("/customer-links")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<List<YouzanCustomerLink>> customerLinks(@RequestParam String shopId, HttpServletRequest request) {
        return ApiResponse.ok(managementService.customerLinks(TenantContext.currentTenantId(), shopId), requestId(request));
    }

    @PostMapping("/customer-links")
    @PreAuthorize("hasAuthority('campaign:write')")
    public ApiResponse<YouzanCustomerLink> saveCustomerLink(@Valid @RequestBody YouzanCustomerLinkRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.upsertCustomerLink(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }
}
