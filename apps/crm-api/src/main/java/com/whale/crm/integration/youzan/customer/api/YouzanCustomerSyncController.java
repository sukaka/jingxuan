package com.whale.crm.integration.youzan.customer.api;

import com.whale.crm.integration.youzan.api.YouzanBaseController;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerCursorResetRequest;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerPullRequest;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncItemResponse;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncRunResponse;
import com.whale.crm.integration.youzan.customer.api.dto.YouzanCustomerSyncStatusResponse;
import com.whale.crm.integration.youzan.customer.application.YouzanCustomerSyncService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

/**
 * 有赞客户拉取与 CRM 正式建档管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/integrations/youzan/customer-sync")
public class YouzanCustomerSyncController extends YouzanBaseController {

    private final YouzanCustomerSyncService syncService;

    public YouzanCustomerSyncController(YouzanCustomerSyncService syncService) {
        this.syncService = syncService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanCustomerSyncStatusResponse> status(
            @RequestParam @NotBlank @Size(max = 64) String shopId,
            HttpServletRequest request) {
        return ApiResponse.ok(syncService.getStatus(SecuritySupport.requireCurrentUser(), shopId),
                requestId(request));
    }

    @PostMapping("/pull")
    @PreAuthorize("hasAuthority('integration:youzan:manage') and hasAuthority('customer:write')")
    public ApiResponse<YouzanCustomerSyncRunResponse> pull(
            @Valid @RequestBody YouzanCustomerPullRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(syncService.pull(SecuritySupport.requireCurrentUser(), body, idempotencyKey),
                requestId(request));
    }

    @PostMapping("/cursor/reset")
    @PreAuthorize("hasAuthority('integration:youzan:manage') and hasAuthority('customer:write')")
    public ApiResponse<YouzanCustomerSyncStatusResponse> resetCursor(
            @Valid @RequestBody YouzanCustomerCursorResetRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(syncService.resetCursor(
                SecuritySupport.requireCurrentUser(), body.shopId(), body.sourceMode()),
                requestId(request));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<PageResponse<YouzanCustomerSyncRunResponse>> runs(
            @RequestParam @NotBlank @Size(max = 64) String shopId,
            @RequestParam(required = false) @Size(max = 32) String sourceMode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(syncService.listRuns(
                SecuritySupport.requireCurrentUser(), shopId, sourceMode, page, size),
                requestId(request));
    }

    @GetMapping("/runs/{runId}/items")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<PageResponse<YouzanCustomerSyncItemResponse>> runItems(
            @PathVariable @Min(1) long runId,
            @RequestParam(required = false) @Size(max = 32) String outcome,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(syncService.listRunItems(
                SecuritySupport.requireCurrentUser(), runId, outcome, page, size),
                requestId(request));
    }
}
