package com.whale.crm.customer.api;

import com.whale.crm.customer.api.dto.CreateCustomerRequest;
import com.whale.crm.customer.api.dto.CreateTransferRequest;
import com.whale.crm.customer.api.dto.CustomerDetailResponse;
import com.whale.crm.customer.api.dto.CustomerListItemResponse;
import com.whale.crm.customer.api.dto.CustomerPointsResponse;
import com.whale.crm.customer.api.dto.OwnershipHistoryItemResponse;
import com.whale.crm.customer.api.dto.PointsAdjustmentRequest;
import com.whale.crm.customer.api.dto.TransferItemResponse;
import com.whale.crm.customer.api.dto.UpdateCustomerRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerTagsRequest;
import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.TransferApplicationService;
import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 客户中心（customer:read/write/transfer/points_adjust）。
 * 手机号仅 mobileMasked 出参；所有读写走 DataScopeService 行级范围；ownerId 只允许收窄。
 */
@Validated
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerApplicationService customerService;
    private final TransferApplicationService transferService;

    public CustomerController(CustomerApplicationService customerService,
                              TransferApplicationService transferService) {
        this.customerService = customerService;
        this.transferService = transferService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<PageResponse<CustomerListItemResponse>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 64) String keyword,
            @RequestParam(required = false) @Size(max = 64) String status,
            @RequestParam(required = false) @Size(max = 64) String cityCode,
            @RequestParam(required = false) @Size(max = 64) String sourceCode,
            @RequestParam(required = false) @Size(max = 64) String channelCode,
            @RequestParam(required = false) @Size(max = 16) String tierCode,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant followupDueBefore,
            HttpServletRequest request) {
        return ApiResponse.ok(customerService.search(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), keyword, status, cityCode, sourceCode, channelCode, tierCode,
                ownerId, followupDueBefore, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer:write')")
    public ResponseEntity<ApiResponse<CustomerDetailResponse>> create(
            @Valid @RequestBody CreateCustomerRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        CustomerDetailResponse created = customerService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), body, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerDetailResponse> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(customerService.detail(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerDetailResponse> update(@PathVariable long id,
                                                      @Valid @RequestBody UpdateCustomerRequest body,
                                                      HttpServletRequest request) {
        return ApiResponse.ok(customerService.update(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PutMapping("/{id}/tags")
    @PreAuthorize("hasAuthority('customer:write')")
    public ApiResponse<CustomerDetailResponse> replaceTags(@PathVariable long id,
                                                           @Valid @RequestBody UpdateCustomerTagsRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.ok(customerService.replaceTags(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body.tagCodes()), requestId(request));
    }

    @GetMapping("/{id}/points")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<CustomerPointsResponse> points(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(customerService.points(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/points-adjustments")
    @PreAuthorize("hasAuthority('customer:points_adjust')")
    public ApiResponse<CustomerPointsResponse> adjustPoints(
            @PathVariable long id,
            @Valid @RequestBody PointsAdjustmentRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(customerService.adjustPoints(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body, idempotencyKey), requestId(request));
    }

    @GetMapping("/{id}/ownership-history")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<OwnershipHistoryItemResponse>> ownershipHistory(@PathVariable long id,
                                                                            HttpServletRequest request) {
        return ApiResponse.ok(customerService.ownershipHistory(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @GetMapping("/{id}/ownership-transfers")
    @PreAuthorize("hasAuthority('customer:read')")
    public ApiResponse<List<TransferItemResponse>> ownershipTransfers(@PathVariable long id,
                                                                      HttpServletRequest request) {
        return ApiResponse.ok(transferService.listByCustomer(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/ownership-transfers")
    @PreAuthorize("hasAuthority('customer:transfer')")
    public ResponseEntity<ApiResponse<TransferItemResponse>> createTransfer(
            @PathVariable long id, @Valid @RequestBody CreateTransferRequest body, HttpServletRequest request) {
        TransferItemResponse created = transferService.create(TenantContext.currentTenantId(),
                SecuritySupport.requireCurrentUser(), id, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return attr instanceof String s ? s : null;
    }
}
