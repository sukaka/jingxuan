package com.whale.crm.integration.wecom.tag.api;

import com.whale.crm.integration.wecom.api.WecomBaseController;
import com.whale.crm.integration.wecom.tag.api.dto.UpdateWecomTagMappingRequest;
import com.whale.crm.integration.wecom.tag.api.dto.WecomCustomerTagPushResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagMappingItemResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagMappingOverviewResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagPushTaskResponse;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagSyncRequest;
import com.whale.crm.integration.wecom.tag.api.dto.WecomTagSyncRunResponse;
import com.whale.crm.integration.wecom.tag.application.WecomTagSyncService;
import com.whale.crm.integration.wecom.tag.domain.WecomTagSyncRun;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

/**
 * 企业微信企业标签双向同步接口（仅官方企微通道与本地开发 Mock 切换，严禁个人微信）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/integrations/wecom/tag-sync")
public class WecomTagSyncController extends WecomBaseController {

    private final WecomTagSyncService tagSyncService;

    public WecomTagSyncController(WecomTagSyncService tagSyncService) {
        this.tagSyncService = tagSyncService;
    }

    @GetMapping("/mappings")
    @PreAuthorize("hasAnyAuthority('customer:tag:manage', 'wecom:read')")
    public ApiResponse<WecomTagMappingOverviewResponse> mappings(HttpServletRequest request) {
        return ApiResponse.ok(tagSyncService.getMappingsOverview(TenantContext.currentTenantId()),
                requestId(request));
    }

    @PutMapping("/mappings/{crmTagCode}")
    @PreAuthorize("hasAnyAuthority('customer:tag:manage', 'wecom:monitor')")
    public ApiResponse<WecomTagMappingItemResponse> updateMapping(
            @PathVariable @Size(min = 1, max = 64) String crmTagCode,
            @Valid @RequestBody UpdateWecomTagMappingRequest body,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(tagSyncService.updateMapping(
                TenantContext.currentTenantId(), user.userId(), crmTagCode, body), requestId(request));
    }

    @PostMapping("/catalog")
    @PreAuthorize("hasAnyAuthority('customer:tag:manage', 'wecom:monitor')")
    public ApiResponse<WecomTagSyncRunResponse> syncCatalog(
            @Valid @RequestBody(required = false) WecomTagSyncRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        String direction = body == null ? null : body.direction();
        return ApiResponse.ok(tagSyncService.syncCatalog(
                TenantContext.currentTenantId(), user.userId(), direction,
                WecomTagSyncRun.TRIGGER_MANUAL, idempotencyKey), requestId(request));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomTagSyncRunResponse> syncCustomers(
            @Valid @RequestBody(required = false) WecomTagSyncRequest body,
            @RequestHeader(name = "Idempotency-Key") @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        String direction = body == null ? null : body.direction();
        return ApiResponse.ok(tagSyncService.syncCustomers(
                TenantContext.currentTenantId(), user, direction,
                WecomTagSyncRun.TRIGGER_MANUAL, idempotencyKey), requestId(request));
    }

    @PostMapping("/customers/{customerId}/push")
    @PreAuthorize("hasAnyAuthority('customer:write', 'wecom:monitor')")
    public ApiResponse<WecomCustomerTagPushResponse> pushCustomer(
            @PathVariable long customerId,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(tagSyncService.pushCustomerTags(
                TenantContext.currentTenantId(), user, customerId, idempotencyKey), requestId(request));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAnyAuthority('customer:tag:manage', 'wecom:read')")
    public ApiResponse<List<WecomTagSyncRunResponse>> runs(HttpServletRequest request) {
        return ApiResponse.ok(tagSyncService.listRuns(TenantContext.currentTenantId()), requestId(request));
    }

    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<List<WecomTagPushTaskResponse>> tasks(
            @RequestParam(required = false) @Size(max = 32) String status,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(tagSyncService.listTasks(TenantContext.currentTenantId(), user, status),
                requestId(request));
    }

    @PostMapping("/tasks/{taskId}/retry")
    @PreAuthorize("hasAuthority('wecom:monitor')")
    public ApiResponse<WecomTagPushTaskResponse> retryTask(
            @PathVariable long taskId,
            HttpServletRequest request) {
        var user = SecuritySupport.requireCurrentUser();
        return ApiResponse.ok(tagSyncService.retryTask(
                TenantContext.currentTenantId(), user, taskId), requestId(request));
    }
}
