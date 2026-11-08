package com.whale.crm.integration.youzan.api;

import com.whale.crm.integration.youzan.api.dto.YouzanShopConfigRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanShopConfigView;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncPolicyRequest;
import com.whale.crm.integration.youzan.application.YouzanConnectionService;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.integration.youzan.domain.YouzanSyncPolicy;
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
@PreAuthorize("hasAuthority('integration:youzan:manage')")
public class YouzanConnectionController extends YouzanBaseController {
    private final YouzanConnectionService connectionService;

    public YouzanConnectionController(YouzanConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @GetMapping("/shops")
    public ApiResponse<List<YouzanShopConfigView>> shops(HttpServletRequest request) {
        return ApiResponse.ok(connectionService.shops(SecuritySupport.requireCurrentUser().tenantId()), requestId(request));
    }

    @PostMapping("/shops")
    public ApiResponse<YouzanShopConfigView> saveShop(@Valid @RequestBody YouzanShopConfigRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(connectionService.saveShop(SecuritySupport.requireCurrentUser(), body, key), requestId(request));
    }

    @PostMapping("/shops/{shopId}/test")
    public ApiResponse<YouzanShopConfigView> test(@PathVariable String shopId,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(connectionService.testConnection(SecuritySupport.requireCurrentUser(), shopId), requestId(request));
    }

    @GetMapping("/shops/{shopId}/sync-policy")
    public ApiResponse<YouzanSyncPolicy> policy(@PathVariable String shopId, HttpServletRequest request) {
        return ApiResponse.ok(connectionService.policy(SecuritySupport.requireCurrentUser().tenantId(), shopId), requestId(request));
    }

    @PutMapping("/shops/{shopId}/sync-policy")
    public ApiResponse<YouzanSyncPolicy> savePolicy(@PathVariable String shopId,
            @Valid @RequestBody YouzanSyncPolicyRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String key,
            HttpServletRequest request) {
        return ApiResponse.ok(connectionService.savePolicy(SecuritySupport.requireCurrentUser(), shopId, body), requestId(request));
    }

    @GetMapping("/sync-jobs")
    public ApiResponse<PageResponse<YouzanSyncJob>> jobs(@RequestParam String shopId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        return ApiResponse.ok(connectionService.jobs(SecuritySupport.requireCurrentUser().tenantId(), shopId, page, size), requestId(request));
    }
}
