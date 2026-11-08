package com.whale.crm.trading.api;

import com.whale.crm.platform.context.TenantContext;
import com.whale.crm.platform.security.SecuritySupport;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.platform.web.RequestIdFilter;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderReviewRequest;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.PaymentApplicationService;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderApplicationService orderService;
    private final PaymentApplicationService paymentService;

    public OrderController(OrderApplicationService orderService, PaymentApplicationService paymentService) {
        this.orderService = orderService;
        this.paymentService = paymentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<PageResponse<Order>> search(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) @Size(max = 48) String status,
            HttpServletRequest request) {
        return ApiResponse.ok(orderService.search(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                customerId, status, page, size), requestId(request));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('order:write')")
    public ResponseEntity<ApiResponse<OrderFulfillmentView>> create(@Valid @RequestBody CreateOrderRequest body,
                                                                    HttpServletRequest request) {
        OrderFulfillmentView created = orderService.create(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created, requestId(request)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:read')")
    public ApiResponse<OrderFulfillmentView> detail(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(orderService.detail(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id), requestId(request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('order:write')")
    public ApiResponse<OrderFulfillmentView> submit(@PathVariable long id, @Valid @RequestBody OrderReviewRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(orderService.submit(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:write')")
    public ApiResponse<OrderFulfillmentView> cancel(@PathVariable long id, @Valid @RequestBody OrderReviewRequest body,
                                                    HttpServletRequest request) {
        return ApiResponse.ok(orderService.cancel(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(), id, body), requestId(request));
    }

    @PostMapping("/{id}/payment-confirmations")
    @PreAuthorize("hasAuthority('order:confirm_payment')")
    public ApiResponse<OrderFulfillmentView> confirmPayment(
            @PathVariable long id, @Valid @RequestBody PaymentConfirmRequest body,
            @RequestHeader(name = "Idempotency-Key", required = true) @Size(min = 1, max = 128) String idempotencyKey,
            HttpServletRequest request) {
        return ApiResponse.ok(paymentService.confirm(TenantContext.currentTenantId(), SecuritySupport.requireCurrentUser(),
                id, body, idempotencyKey), requestId(request));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> missingRequestHeader(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.VALIDATION_FAILED.name(),
                "支付确认必须提供幂等键", null, requestId(request)));
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
