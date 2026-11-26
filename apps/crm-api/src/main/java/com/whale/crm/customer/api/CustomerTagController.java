package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateCustomerTagRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerTagRequest;
import com.whale.crm.customer.application.CustomerTagAdminService;
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
 * 客户标签内容管理（仅 customer:tag:manage：管家主管 / 系统管理员）。
 * 普通管家仅通过只读字典选项接口选择标签，无权新增、修改或启停标签内容。
 */
@Validated
@RestController
@RequestMapping("/api/v1/customer-tags")
public class CustomerTagController {

    private final CustomerTagAdminService customerTagAdminService;

    public CustomerTagController(CustomerTagAdminService customerTagAdminService) {
        this.customerTagAdminService = customerTagAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:tag:manage')")
    public ApiResponse<List<DictionaryItemResponse>> list(HttpServletRequest request) {
        return ApiResponse.ok(customerTagAdminService.listTags(TenantContext.currentTenantId()), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:tag:manage')")
    public ResponseEntity<ApiResponse<DictionaryItemResponse>> create(
            @Valid @RequestBody CreateCustomerTagRequest body,
            HttpServletRequest request) {
        DictionaryItemResponse created = customerTagAdminService.createTag(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:tag:manage')")
    public ApiResponse<DictionaryItemResponse> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateCustomerTagRequest body,
            HttpServletRequest request) {
        return ApiResponse.ok(customerTagAdminService.updateTag(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id, body),
                requestId(request));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasAuthority('customer:tag:manage')")
    public ApiResponse<DictionaryItemResponse> disable(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(customerTagAdminService.disableTag(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasAuthority('customer:tag:manage')")
    public ApiResponse<DictionaryItemResponse> enable(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(customerTagAdminService.enableTag(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
