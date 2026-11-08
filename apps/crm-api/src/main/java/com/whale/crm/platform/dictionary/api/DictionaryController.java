package com.whale.crm.platform.dictionary.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典选项只读查询（dictionary:read，所有登录角色具备）。停用/归档项不出现在该列表。
 */
@Validated
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryController {

    private final DictionaryApplicationService dictionaryService;

    public DictionaryController(DictionaryApplicationService dictionaryService) {
        this.dictionaryService = dictionaryService;
    }

    @GetMapping("/{dictCode}/options")
    @PreAuthorize("hasAuthority('dictionary:read')")
    public ApiResponse<List<DictionaryOptionResponse>> options(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "字典编码格式无效") String dictCode,
            HttpServletRequest request) {
        List<DictionaryOptionResponse> options =
                dictionaryService.listActiveOptions(TenantContext.currentTenantId(), dictCode);
        return ApiResponse.ok(options, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
