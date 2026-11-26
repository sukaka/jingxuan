package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CustomerOwnershipLogItemResponse;
import com.whale.crm.customer.application.CustomerAssignmentApplicationService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户生效归属日志分页查询（customer:read + 行级数据范围），统一展示主管直接分配、批量分配与转交审批生效记录。
 */
@Validated
@RestController
@RequestMapping("/api/v1/customer-ownership-history")
public class CustomerOwnershipLogController {

    private final CustomerAssignmentApplicationService assignmentService;

    public CustomerOwnershipLogController(CustomerAssignmentApplicationService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerOwnershipLogItemResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            HttpServletRequest request) {
        return ApiResponse.ok(assignmentService.searchOwnershipLogs(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), customerId, ownerId, keyword, page, size), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
