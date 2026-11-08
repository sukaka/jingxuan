package com.whale.crm.platform.dictionary.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.dictionary.api.dto.CreateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.DictionaryTypeResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典管理（dictionary:write）。只读选项接口见 DictionaryController（dictionary:read）。
 * 停用不破历史：INACTIVE 项不出现在 options，但保留在管理列表与历史单据关联中。
 */
@RestController
@RequestMapping("/api/v1")
public class DictionaryAdminController {

    private final DictionaryApplicationService dictionaryService;

    public DictionaryAdminController(DictionaryApplicationService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/dictionary-types")
    @PreAuthorize("hasAuthority('dictionary:write')")
    public ApiResponse<List<DictionaryTypeResponse>> listTypes(HttpServletRequest request) {
        return ApiResponse.ok(dictionaryService.listTypesWithItems(TenantContext.currentTenantId()),
                requestId(request));
    }

    @GetMapping("/dictionary-types/{id}/items")
    @PreAuthorize("hasAuthority('dictionary:read')")
    public ApiResponse<List<DictionaryItemResponse>> listTypeItems(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(dictionaryService.listItemsOfType(TenantContext.currentTenantId(), id),
                requestId(request));
    }

    @PostMapping("/dictionary-items")
    @PreAuthorize("hasAuthority('dictionary:write')")
    public ResponseEntity<ApiResponse<DictionaryItemResponse>> createItem(
            @Valid @RequestBody CreateDictionaryItemRequest body, HttpServletRequest request) {
        DictionaryItemResponse created = dictionaryService.createItem(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @PatchMapping("/dictionary-items/{id}")
    @PreAuthorize("hasAuthority('dictionary:write')")
    public ApiResponse<DictionaryItemResponse> updateItem(@PathVariable long id,
                                                          @Valid @RequestBody UpdateDictionaryItemRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.ok(dictionaryService.updateItem(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id, body),
                requestId(request));
    }

    @PostMapping("/dictionary-items/{id}/disable")
    @PreAuthorize("hasAuthority('dictionary:write')")
    public ApiResponse<DictionaryItemResponse> disableItem(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(dictionaryService.disableItem(
                TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser().userId(), id),
                requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
