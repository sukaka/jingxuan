package com.whale.crm.integration.youzan.api;

import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderAssignRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanPullOrderByTidRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuCandidateView;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasSaveResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasView;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardOptionView;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.application.YouzanOrderAssignmentService;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.security.CurrentUser;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final YouzanOrderAssignmentService assignmentService;

    public YouzanIntegrationController(YouzanManagementService managementService,
                                       YouzanOrderAssignmentService assignmentService) {
        this.managementService = managementService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<PageResponse<YouzanOrderView>> orders(
            @RequestParam String shopId,
            @RequestParam(required = false) Long assignedStewardId,
            @RequestParam(required = false) String assignmentStatus,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.orders(user.tenantId(), user, shopId,
                assignedStewardId, assignmentStatus, keyword, page, size), requestId(request));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<YouzanOrderView> order(@PathVariable long id, HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.order(user.tenantId(), user, id), requestId(request));
    }

    @PostMapping("/orders/pull-by-tid")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanOrderView> pullOrderByTid(
            @Valid @RequestBody YouzanPullOrderByTidRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.pullOrderByTid(SecuritySupport.requireCurrentUser(), body, key),
                requestId(request));
    }

    @PutMapping("/orders/{externalOrderNo}/assign")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'customer:assign', 'campaign:write')")
    public ApiResponse<YouzanOrderView> assignOrderSteward(
            @PathVariable String externalOrderNo,
            @RequestParam String shopId,
            @Valid @RequestBody YouzanOrderAssignRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.assignOrderSteward(
                SecuritySupport.requireCurrentUser(), shopId, externalOrderNo, body, key), requestId(request));
    }

    @GetMapping("/steward-options")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<List<YouzanStewardOptionView>> stewardOptions(HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(assignmentService.stewardOptions(user.tenantId(), user), requestId(request));
    }

    @GetMapping("/steward-aliases")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<List<YouzanStewardAliasView>> stewardAliases(HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(assignmentService.listAliases(user.tenantId(), user), requestId(request));
    }

    @PostMapping("/steward-aliases")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanStewardAliasSaveResponse> saveStewardAlias(
            @Valid @RequestBody YouzanStewardAliasRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(assignmentService.saveAlias(SecuritySupport.requireCurrentUser(), body, key),
                requestId(request));
    }

    @DeleteMapping("/steward-aliases/{id}")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<Void> deleteStewardAlias(@PathVariable long id, HttpServletRequest request) {
        assignmentService.deleteAlias(SecuritySupport.requireCurrentUser(), id);
        return ApiResponse.ok(null, requestId(request));
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
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<YouzanReconcileReport> report(@RequestParam String shopId, HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.report(user.tenantId(), user, shopId), requestId(request));
    }

    @GetMapping("/sync-issues")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<PageResponse<YouzanSyncIssue>> issues(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.issues(user.tenantId(), user, status, page, size), requestId(request));
    }

    @PostMapping("/sync-issues/{id}/retry")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'campaign:write')")
    public ApiResponse<YouzanEventResponse> retry(@PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.retryIssue(SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/sync-issues/batch-retry")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'campaign:write')")
    public ApiResponse<YouzanBatchRetryResponse> batchRetry(@Valid @RequestBody YouzanBatchRetryRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.batchRetryMappingIssues(SecuritySupport.requireCurrentUser(), body, key),
                requestId(request));
    }

    @GetMapping("/sku-candidates")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<List<YouzanSkuCandidateView>> skuCandidates(@RequestParam String shopId,
            HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.skuCandidates(user.tenantId(), user, shopId), requestId(request));
    }

    @GetMapping("/sku-mappings")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<List<YouzanSkuMapping>> skuMappings(@RequestParam String shopId, HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.skuMappings(user.tenantId(), user, shopId), requestId(request));
    }

    @PostMapping("/sku-mappings")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'campaign:write')")
    public ApiResponse<YouzanSkuMapping> saveSkuMapping(@Valid @RequestBody YouzanSkuMappingRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.upsertSkuMapping(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @GetMapping("/customer-links")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'order:read')")
    public ApiResponse<List<YouzanCustomerLink>> customerLinks(@RequestParam String shopId, HttpServletRequest request) {
        CurrentUser user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(managementService.customerLinks(user.tenantId(), user, shopId), requestId(request));
    }

    @PostMapping("/customer-links")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'campaign:write')")
    public ApiResponse<YouzanCustomerLink> saveCustomerLink(@Valid @RequestBody YouzanCustomerLinkRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(managementService.upsertCustomerLink(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }
}
