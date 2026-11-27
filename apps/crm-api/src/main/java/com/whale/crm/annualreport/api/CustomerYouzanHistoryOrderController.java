package com.whale.crm.annualreport.api;

import com.whale.crm.annualreport.api.dto.CustomerYouzanHistoryOrdersResponse;
import com.whale.crm.annualreport.application.CustomerYouzanHistoryOrderService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户详情页「有赞历史订单（只读快照）」查询接口（customer:read + 行级数据范围，严禁机构账号访问）。
 */
@Validated
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerYouzanHistoryOrderController {

    private final CustomerYouzanHistoryOrderService historyOrderService;

    public CustomerYouzanHistoryOrderController(CustomerYouzanHistoryOrderService historyOrderService) {
        this.historyOrderService = historyOrderService;
    }

    @GetMapping("/{customerId}/youzan-history-orders")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerYouzanHistoryOrdersResponse> list(
            @PathVariable @Min(1) long customerId,
            @RequestParam(defaultValue = "true") boolean paidOnly,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            HttpServletRequest request) {
        CustomerYouzanHistoryOrdersResponse data = historyOrderService.listCustomerHistoryOrders(
                TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(),
                customerId,
                paidOnly,
                page,
                size);
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return ApiResponse.ok(data, requestId instanceof String text ? text : null);
    }
}
