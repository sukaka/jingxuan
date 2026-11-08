package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.ApproveTransferRequest;
import com.whale.crm.customer.api.dto.RejectTransferRequest;
import com.whale.crm.customer.api.dto.TransferItemResponse;
import com.whale.crm.customer.application.TransferApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户转交审批（customer:transfer）。approve 才在同一事务写归属与不可覆盖历史；reject 留痕。
 */
@RestController
@RequestMapping("/api/v1/customer-ownership-transfers")
public class CustomerTransferReviewController {

    private final TransferApplicationService transferService;

    public CustomerTransferReviewController(TransferApplicationService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('customer:transfer')")
    public ApiResponse<TransferItemResponse> approve(@PathVariable long id,
                                                     @Valid @RequestBody ApproveTransferRequest body,
                                                     HttpServletRequest request) {
        return ApiResponse.ok(transferService.approve(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('customer:transfer')")
    public ApiResponse<TransferItemResponse> reject(@PathVariable long id,
                                                    @Valid @RequestBody RejectTransferRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(transferService.reject(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
