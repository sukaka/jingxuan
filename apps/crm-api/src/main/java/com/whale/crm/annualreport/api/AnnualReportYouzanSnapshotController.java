package com.whale.crm.annualreport.api;

import com.whale.crm.annualreport.api.dto.YouzanAnnualProfileItemResponse;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSummaryResponse;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSyncRequest;
import com.whale.crm.annualreport.api.dto.YouzanAnnualSnapshotSyncResponse;
import com.whale.crm.annualreport.application.YouzanAnnualSnapshotService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端有赞历史订单只读快照拉取与买家档案管理接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/annual-reports/youzan-snapshots")
public class AnnualReportYouzanSnapshotController {

    private final YouzanAnnualSnapshotService snapshotService;

    public AnnualReportYouzanSnapshotController(YouzanAnnualSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('integration:youzan:manage')")
    public ApiResponse<YouzanAnnualSnapshotSyncResponse> sync(
            @Valid @RequestBody YouzanAnnualSnapshotSyncRequest body,
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(
                snapshotService.syncSnapshots(SecuritySupport.requireCurrentUser(), body, idempotencyKey),
                requestId(request));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'customer:read')")
    public ApiResponse<YouzanAnnualSnapshotSummaryResponse> summary(
            @RequestParam(required = false) @Size(max = 64) String shopId,
            HttpServletRequest request) {
        return ApiResponse.ok(
                snapshotService.summary(SecuritySupport.requireCurrentUser(), shopId),
                requestId(request));
    }

    @GetMapping("/profiles")
    @PreAuthorize("hasAnyAuthority('integration:youzan:manage', 'customer:read')")
    public ApiResponse<PageResponse<YouzanAnnualProfileItemResponse>> profiles(
            @RequestParam(required = false) @Size(max = 64) String shopId,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(
                snapshotService.listProfiles(SecuritySupport.requireCurrentUser(), shopId, keyword, page, size),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String text ? text : null;
    }
}
