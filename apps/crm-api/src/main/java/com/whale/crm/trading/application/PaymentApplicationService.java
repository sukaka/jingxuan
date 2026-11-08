package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerOrderOperations;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.PaymentTxn;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PaymentApplicationService {
    private static final String IDEMPOTENCY_SCOPE = "payment.confirm";
    private static final String PROVIDER_IDEMPOTENCY_SCOPE = "payment.provider_callback";

    private final TradingRepository repository;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final CustomerOrderOperations customerOrderOperations;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final OrderApplicationService orderService;
    private final FileApplicationService fileOperations;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public PaymentApplicationService(TradingRepository repository, IdempotencyService idempotencyService,
                                     OutboxService outboxService, CustomerOrderOperations customerOrderOperations,
                                     CustomerScopeSupport scopeSupport, AuditService auditService,
                                     OrderApplicationService orderService, FileApplicationService fileOperations, ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.customerOrderOperations = customerOrderOperations;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.orderService = orderService;
        this.fileOperations = fileOperations;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public OrderFulfillmentView confirm(long tenantId, CurrentUser user, long orderId,
                                        PaymentConfirmRequest request, String idempotencyKey) {
        return confirmInternal(tenantId, user, orderId, request, idempotencyKey,
                IDEMPOTENCY_SCOPE, "MANUAL_REVIEW", "人工凭证复核");
    }

    public OrderFulfillmentView confirmProviderCallback(long tenantId, CurrentUser user, long orderId,
                                                        PaymentConfirmRequest request, String idempotencyKey) {
        if (!"YOUZAN".equals(request.paymentChannel())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "供应商支付渠道必须为YOUZAN");
        }
        return confirmInternal(tenantId, user, orderId, request, idempotencyKey,
                PROVIDER_IDEMPOTENCY_SCOPE, "PROVIDER_CALLBACK", "有赞支付回调");
    }

    private OrderFulfillmentView confirmInternal(long tenantId, CurrentUser user, long orderId,
                                                 PaymentConfirmRequest request, String idempotencyKey,
                                                 String scope, String verificationMethod, String auditReason) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "支付确认必须提供幂等键");
        }
        String key = idempotencyKey.strip();
        String payload = canonical(orderId, request);
        Optional<IdempotencyRecord> replay = idempotencyService.lookup(tenantId, scope, key, payload);
        if (replay.isPresent()) {
            return orderService.detail(tenantId, user, orderId);
        }
        try {
            return transactionTemplate.execute(status -> doConfirm(tenantId, user, orderId, request, key, payload,
                    scope, verificationMethod, auditReason));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayAfterDuplicate(tenantId, user, orderId, request,
                    key, payload, scope));
        }
    }

    private OrderFulfillmentView doConfirm(long tenantId, CurrentUser user, long orderId,
                                           PaymentConfirmRequest request, String key, String payload,
                                           String scope, String verificationMethod, String auditReason) {
        Order before = repository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        scopeSupport.requireVisible(tenantId, before.customerId(), user);
        if (idempotencyService.lookup(tenantId, scope, key, payload).isPresent()) {
            return orderService.detail(tenantId, user, orderId);
        }
        if (List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(before.status())) {
            return replayExisting(tenantId, user, orderId, request, key, payload, scope);
        }
        if (!"PENDING_PAYMENT".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单未处于待支付状态，不能确认支付或发放权益");
        }
        if (!request.amountFen().equals(before.payableFen())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "支付金额必须与订单应付金额一致；当前阶段不支持部分支付");
        }
        repository.findPaymentByTradeNo(tenantId, request.paymentChannel(), request.externalTradeNo())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "支付交易号已被其他订单使用");
                });

        if (request.voucherFileId() != null) {
            fileOperations.requireCleanUsable(tenantId, user, request.voucherFileId(), FileClassification.PAYMENT, null);
        }
        PaymentTxn input = new PaymentTxn(null, orderId, request.paymentChannel(), before.receiverType(),
                request.externalTradeNo(), request.amountFen(), "CONFIRMED", Instant.now(), user.userId(),
                request.voucherFileId(), request.evidenceRef(), verificationMethod, null, null);
        repository.insertPayment(input);
        PaymentTxn payment = repository.findPaymentByTradeNo(tenantId, request.paymentChannel(), request.externalTradeNo())
                .orElseThrow();
        if (repository.updateOrderStatus(tenantId, orderId, before.version(), "PENDING_PAYMENT", "PAID",
                request.amountFen(), request.paidAt()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单已被其他流程修改，请核对后重试");
        }
        repository.insertStatusHistory(new OrderStatusHistory(null, orderId, before.status(), "PAID", user.userId(),
                "PAYMENT_CONFIRMED", payment.id().toString(), auditReason, null));

        List<OrderItem> items = repository.findOrderItems(tenantId, orderId);
        for (OrderItem item : items) {
            grantEntitlements(tenantId, user, before, payment, item);
        }
        outboxService.append(tenantId, "order.paid.v1:" + orderId, "order.paid.v1", "order", Long.toString(orderId),
                Map.of("orderId", orderId, "paymentId", payment.id(), "customerId", before.customerId(),
                        "amountFen", request.amountFen(), "verificationMethod", verificationMethod));
        customerOrderOperations.recordFirstOrder(tenantId, user, before.customerId(), request.paidAt());
        idempotencyService.recordSuccess(tenantId, scope, key, "PAYMENT", payment.id().toString(), payload);
        OrderFulfillmentView after = orderService.detail(tenantId, user, orderId);
        auditService.record(tenantId, user.userId(), "PAYMENT_CONFIRM", "ORDER", Long.toString(orderId), before, after,
                auditReason + ": " + request.evidenceRef());
        return after;
    }

    private void grantEntitlements(long tenantId, CurrentUser user, Order order, PaymentTxn payment, OrderItem item) {
        try {
            JsonNode snapshot = objectMapper.readTree(item.entitlementSnapshot());
            for (JsonNode project : snapshot.path("projectSnapshots")) {
                int granted = project.path("grantCount").asInt();
                if (granted <= 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "权益快照缺少有效发放次数");
                }
                Entitlement entitlement = new Entitlement(null, order.customerId(), item.id(), project.path("projectId").asLong(),
                        null, granted, granted, 0, parseDate(snapshot.path("validFrom").asText(null)),
                        parseDate(snapshot.path("validTo").asText(null)), "AVAILABLE", null, null);
                repository.insertEntitlement(entitlement);
                Entitlement persisted = repository.findEntitlementsByOrderItemIds(tenantId, List.of(item.id())).stream()
                        .filter(row -> row.projectId().equals(entitlement.projectId())).findFirst().orElseThrow();
                String key = "entitlement.grant:" + order.id() + ":" + item.id() + ":" + entitlement.projectId();
                repository.insertLedger(new EntitlementLedgerEntry(null, persisted.id(), "GRANT", granted, "PAYMENT",
                        payment.id().toString(), key, user.userId(), "支付确认发放", null, null));
            }
        } catch (DuplicateKeyException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("权益快照解析失败", ex);
        }
    }

    private OrderFulfillmentView replayAfterDuplicate(long tenantId, CurrentUser user, long orderId,
                                                      PaymentConfirmRequest request, String key, String payload,
                                                      String scope) {
        Order order = repository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        scopeSupport.requireVisible(tenantId, order.customerId(), user);
        return replayExisting(tenantId, user, orderId, request, key, payload, scope);
    }

    private OrderFulfillmentView replayExisting(long tenantId, CurrentUser user, long orderId,
                                                PaymentConfirmRequest request, String key, String payload,
                                                String scope) {
        idempotencyService.lookup(tenantId, scope, key, payload)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "当前幂等键没有对应的已提交支付结果"));
        PaymentTxn existing = repository.findPaymentByTradeNo(tenantId, request.paymentChannel(), request.externalTradeNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "支付正在处理，请稍后查询结果"));
        if (!existing.orderId().equals(orderId) || !existing.amountFen().equals(request.amountFen())) {
            throw new BusinessException(ErrorCode.CONFLICT, "交易号对应的订单或金额不一致");
        }
        return orderService.detail(tenantId, user, orderId);
    }

    private String canonical(long orderId, PaymentConfirmRequest request) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("orderId", orderId);
            canonical.put("externalTradeNo", request.externalTradeNo());
            canonical.put("paymentChannel", request.paymentChannel());
            canonical.put("amountFen", request.amountFen());
            canonical.put("paidAt", request.paidAt().toString());
            canonical.put("evidenceRef", request.evidenceRef());
            canonical.put("voucherFileId", request.voucherFileId());
            return objectMapper.writeValueAsString(canonical);
        } catch (Exception ex) {
            throw new IllegalStateException("支付请求规范化失败", ex);
        }
    }

    private LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }
}
