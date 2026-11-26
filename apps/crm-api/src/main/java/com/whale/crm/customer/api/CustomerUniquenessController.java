package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CustomerStewardSummaryResponse;
import com.whale.crm.customer.api.dto.MultiStewardCustomerGroupResponse;
import com.whale.crm.customer.api.dto.MultiStewardSummaryResponse;
import com.whale.crm.customer.application.CustomerUniquenessService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户唯一性只读排查接口（customer:merge：管家主管 / 系统管理员）。
 * 展示同一顾客添加了哪几位管家及所属团队，无需合并客户档案；跨团队仅返回管家与团队汇总，不泄露跨组敏感详情。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CustomerUniquenessController {

    private final CustomerUniquenessService uniquenessService;

    public CustomerUniquenessController(CustomerUniquenessService uniquenessService) {
        this.uniquenessService = uniquenessService;
    }

    @GetMapping("/customer-uniqueness/summary")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<MultiStewardSummaryResponse> summary(HttpServletRequest request) {
        return ApiResponse.ok(uniquenessService.summary(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser()), requestId(request));
    }

    @GetMapping("/customer-uniqueness/multi-stewards")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<PageResponse<MultiStewardCustomerGroupResponse>> listMultiStewards(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(defaultValue = "2") @Min(2) @Max(50) int minStewards,
            @RequestParam(defaultValue = "false") boolean crossTeamOnly,
            HttpServletRequest request) {
        return ApiResponse.ok(uniquenessService.listMultiStewardGroups(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), keyword, minStewards, crossTeamOnly, page, size),
                requestId(request));
    }

    @GetMapping("/customers/{id}/steward-relations")
    @PreAuthorize("hasAuthority('customer:merge')")
    public ApiResponse<CustomerStewardSummaryResponse> customerStewards(@PathVariable long id,
                                                                        HttpServletRequest request) {
        return ApiResponse.ok(uniquenessService.customerStewards(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
