package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateCustomerOptionRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerOptionRequest;
import com.whale.crm.customer.application.CustomerSourceChannelAdminService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户来源与客户渠道统一管理（仅 customer:source_channel:manage：管家主管 / 系统管理员）。
 * 普通管家仅通过只读字典选项接口下拉选择，无权新增、修改或启停来源/渠道选项。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CustomerSourceChannelController {

    private final CustomerSourceChannelAdminService adminService;

    public CustomerSourceChannelController(CustomerSourceChannelAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/customer-sources")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<List<DictionaryItemResponse>> listSources(HttpServletRequest request) {
        return ApiResponse.ok(adminService.listSources(TenantContext.currentTenantId()), requestId(request));
    }

    @PostMapping("/customer-sources")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ResponseEntity<ApiResponse<DictionaryItemResponse>> createSource(
            @Valid @RequestBody CreateCustomerOptionRequest body,
            HttpServletRequest request) {
        DictionaryItemResponse created = adminService.createSource(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/customer-sources/{id}")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> updateSource(
            @PathVariable long id,
            @Valid @RequestBody UpdateCustomerOptionRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateSource(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id, body),
                requestId(request));
    }

    @PostMapping("/customer-sources/{id}/disable")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> disableSource(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(adminService.disableSource(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    @PostMapping("/customer-sources/{id}/enable")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> enableSource(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(adminService.enableSource(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    @GetMapping("/customer-channels")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<List<DictionaryItemResponse>> listChannels(HttpServletRequest request) {
        return ApiResponse.ok(adminService.listChannels(TenantContext.currentTenantId()), requestId(request));
    }

    @PostMapping("/customer-channels")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ResponseEntity<ApiResponse<DictionaryItemResponse>> createChannel(
            @Valid @RequestBody CreateCustomerOptionRequest body,
            HttpServletRequest request) {
        DictionaryItemResponse created = adminService.createChannel(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/customer-channels/{id}")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> updateChannel(
            @PathVariable long id,
            @Valid @RequestBody UpdateCustomerOptionRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(adminService.updateChannel(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id, body),
                requestId(request));
    }

    @PostMapping("/customer-channels/{id}/disable")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> disableChannel(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(adminService.disableChannel(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    @PostMapping("/customer-channels/{id}/enable")
    @PreAuthorize("hasAuthority('customer:source_channel:manage')")
    public ApiResponse<DictionaryItemResponse> enableChannel(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(adminService.enableChannel(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
