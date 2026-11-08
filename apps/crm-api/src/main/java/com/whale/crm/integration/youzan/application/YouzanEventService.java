package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.application.PaymentApplicationService;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderItemRequest;
import com.whale.crm.trading.api.dto.OrderReviewRequest;
import com.whale.crm.trading.api.dto.PaymentConfirmRequest;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class YouzanEventService {
    private static final Set<String> SENSITIVE_FIELDS = Set.of("mobile", "phone", "nickname", "name", "displayName",
            "contactName", "address", "idCard", "buyerName", "buyer_phone", "fans_nickname", "receiver_name",
            "receiver_tel", "receiver_phone", "delivery_address", "self_fetch_info", "buyer_name", "id_card_number",
            "id_card_name", "user_name", "user_tel", "address_detail", "salesman_mobile", "salesman_nick_name",
            "service_guide_staff", "service_guide_name", "order_sales_staff", "order_sales_name", "item_sales_staff",
            "outer_user_id", "weixin_openid", "open_user_id", "buyer_message", "trade_memo");

    private final YouzanRepository repository;
    private final ObjectMapper objectMapper;
    private final AesGcmCipher cipher;
    private final OrderApplicationService orderService;
    private final PaymentApplicationService paymentService;
    private final CustomerScopeSupport scopeSupport;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor taskExecutor;

    public YouzanEventService(YouzanRepository repository, ObjectMapper objectMapper, AesGcmCipher cipher,
                              OrderApplicationService orderService, PaymentApplicationService paymentService,
                              CustomerScopeSupport scopeSupport, CurrentUserService currentUserService,
                              AuditService auditService, OutboxService outboxService,
                              PlatformTransactionManager transactionManager,
                              @org.springframework.beans.factory.annotation.Qualifier("youzanSyncExecutor") TaskExecutor taskExecutor) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.scopeSupport = scopeSupport;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.taskExecutor = taskExecutor;
    }

    public YouzanEventResponse ingest(long tenantId, YouzanOrderEvent event, String source, String rawBody) {
        try {
            String normalized = json(event);
            String masked = maskedView(normalized, rawBody);
            String encrypted = cipher.encrypt(normalized);
            String providerMasked = mask(rawBody);
            String providerEncrypted = cipher.encrypt(rawBody);
            return transactionTemplate.execute(status -> {
                YouzanEvent existing = repository.findEventByKey(tenantId, event.shopId(), event.eventId()).orElse(null);
                if (existing != null) {
                    return response(tenantId, existing, true);
                }
                YouzanEvent input = new YouzanEvent(null, event.shopId(), event.eventId(), event.eventType(),
                        event.externalOrderNo(), masked, encrypted, true, source, "RECEIVED", 0, null, null,
                        Instant.now(), null, null, providerMasked, providerEncrypted, null);
                repository.insertEvent(tenantId, input);
                YouzanEvent created = repository.findEventByKey(tenantId, event.shopId(), event.eventId()).orElseThrow();
                boolean duplicateOrder = repository.findOrderMap(tenantId, event.shopId(), event.externalOrderNo()).isPresent();
                return response(tenantId, created, duplicateOrder);
            });
        } catch (DuplicateKeyException ex) {
            YouzanEvent existing = repository.findEventByKey(tenantId, event.shopId(), event.eventId()).orElseThrow();
            return response(tenantId, existing, true);
        }
    }

    public YouzanEventResponse replacePayloadAndProcess(long tenantId, long eventId, YouzanOrderEvent event,
                                                         String providerPayload) {
        String normalized = json(event);
        transactionsExecuteUpdate(tenantId, eventId, event, normalized, providerPayload);
        return process(tenantId, eventId);
    }

    private void transactionsExecuteUpdate(long tenantId, long eventId, YouzanOrderEvent event,
                                           String normalized, String providerPayload) {
        transactionTemplate.executeWithoutResult(status -> {
            YouzanEvent raw = repository.findEventForUpdate(tenantId, eventId).orElseThrow();
            if (repository.updateEventPayload(tenantId, eventId, raw.version(), event.externalOrderNo(),
                    event.eventType(), maskedView(normalized, providerPayload), cipher.encrypt(normalized), mask(providerPayload),
                    cipher.encrypt(providerPayload)) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "有赞消息已被其他任务更新");
            }
        });
    }

    public YouzanEventResponse retryLater(long tenantId, long eventId, Exception error) {
        return transactionTemplate.execute(status -> markRetry(tenantId, eventId, error));
    }

    public void processAsync(long tenantId, long eventId) {
        taskExecutor.execute(() -> process(tenantId, eventId));
    }

    public YouzanEventResponse process(long tenantId, long eventId) {
        try {
            return transactionTemplate.execute(status -> doProcess(tenantId, eventId));
        } catch (ProcessingFailure failure) {
            return transactionTemplate.execute(status -> markFailure(tenantId, eventId, failure.type, failure.getMessage()));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> markDuplicate(tenantId, eventId));
        } catch (BusinessException ex) {
            if (ex.getMessage() != null && (ex.getMessage().contains("金额") || ex.getMessage().contains("Q01"))) {
                return transactionTemplate.execute(status -> markFailure(tenantId, eventId,
                        ex.getMessage().contains("Q01") ? "MAPPING_FAILED" : "PAYMENT_MISMATCH", ex.getMessage()));
            }
            return transactionTemplate.execute(status -> markRetry(tenantId, eventId, ex));
        } catch (Exception ex) {
            return transactionTemplate.execute(status -> markRetry(tenantId, eventId, ex));
        }
    }

    private YouzanEventResponse doProcess(long tenantId, long eventId) {
        YouzanEvent raw = repository.findEventForUpdate(tenantId, eventId).orElseThrow();
        if ("PROCESSED".equals(raw.processStatus())) {
            return response(tenantId, raw, true);
        }
        YouzanOrderEvent event = readEvent(raw.payloadCipher());
        YouzanOrderMap existing = repository.findOrderMap(tenantId, event.shopId(), event.externalOrderNo()).orElse(null);
        if (existing != null && !event.externalUpdatedAt().isAfter(existing.lastExternalUpdatedAt())) {
            return complete(tenantId, eventId, raw, existing.crmOrderId(), true);
        }
        List<OrderItemRequest> items = new ArrayList<>();
        for (YouzanOrderLine line : event.items()) {
            var mapping = repository.findSkuMapping(tenantId, event.shopId(), line.externalSkuId());
            if (mapping.isEmpty() || !"ACTIVE".equals(mapping.get().status()) || line.quantity() == null || line.quantity() <= 0) {
                throw new ProcessingFailure("MAPPING_FAILED", "外部SKU未映射或数量无效: " + line.externalSkuId());
            }
            items.add(new OrderItemRequest(mapping.get().crmSkuId(), line.quantity()));
        }
        if (items.isEmpty()) {
            throw new ProcessingFailure("MAPPING_FAILED", "外部订单缺少商品明细");
        }
        YouzanCustomerLink customer = repository.findCustomerLink(tenantId, event.shopId(), event.externalCustomerId())
                .orElseThrow(() -> new ProcessingFailure("CUSTOMER_UNRESOLVED", "客户尚未人工确认映射"));
        CurrentUser actor = actor(tenantId);
        scopeSupport.requireVisible(tenantId, customer.customerId(), actor);

        OrderFulfillmentView order;
        if (existing == null) {
            CreateOrderRequest create = new CreateOrderRequest(customer.customerId(),
                    event.receiverType() == null ? "PLATFORM" : event.receiverType(),
                    event.orderTypeCode() == null ? "REGULAR" : event.orderTypeCode(), event.campaignId(),
                    event.campaignBatchId(), event.amountFen(),
                    event.validTo() == null ? LocalDate.now(ZoneOffset.UTC).plusYears(1) : event.validTo(), items);
            order = orderService.createExternalOrder(tenantId, actor, create, "YOUZAN", event.shopId(), event.externalOrderNo());
            YouzanOrderMap map = new YouzanOrderMap(null, event.shopId(), event.externalOrderNo(),
                    order.order().order().id(), event.externalUpdatedAt(), null, null);
            repository.insertOrderMap(tenantId, map);
            existing = repository.findOrderMap(tenantId, event.shopId(), event.externalOrderNo()).orElseThrow();
        } else {
            order = orderService.detail(tenantId, actor, existing.crmOrderId());
        }

        if ("ORDER_PAID".equals(event.eventType())) {
            if (event.externalTradeNo() == null || event.externalTradeNo().isBlank() || event.paidAt() == null
                    || event.amountFen() == null || event.amountFen() <= 0) {
                throw new ProcessingFailure("PAYMENT_MISMATCH", "已支付事件缺少可信交易号、金额或支付时间");
            }
            if (!event.amountFen().equals(order.order().order().payableFen())) {
                throw new ProcessingFailure("PAYMENT_MISMATCH", "外部支付金额与订单应付金额不一致");
            }
            if ("DRAFT".equals(order.order().order().status())) {
                order = orderService.submit(tenantId, actor, existing.crmOrderId(),
                        new OrderReviewRequest(order.order().order().version(), "有赞订单确认"));
            }
            if ("PENDING_PAYMENT".equals(order.order().order().status())) {
                PaymentConfirmRequest payment = new PaymentConfirmRequest(event.externalTradeNo(), "YOUZAN",
                        event.amountFen(), event.paidAt(), "youzan.raw:" + eventId, null);
                order = paymentService.confirmProviderCallback(tenantId, actor, existing.crmOrderId(), payment,
                        event.shopId() + ":" + event.externalTradeNo());
            } else if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.order().order().status())) {
                throw new ProcessingFailure("PAYMENT_MISMATCH", "订单当前状态不允许供应商支付确认");
            }
        }
        return complete(tenantId, eventId, raw, existing.crmOrderId(), false);
    }

    private YouzanEventResponse complete(long tenantId, long eventId, YouzanEvent raw, long orderId, boolean duplicate) {
        YouzanOrderEvent event = readEvent(raw.payloadCipher());
        YouzanOrderMap map = repository.findOrderMapForUpdate(tenantId, event.shopId(), event.externalOrderNo()).orElseThrow();
        if (event.externalUpdatedAt().isAfter(map.lastExternalUpdatedAt())) {
            if (repository.updateOrderMapTime(tenantId, map.id(), map.version(), event.externalUpdatedAt()) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "外部订单版本已变化");
            }
        }
        if (repository.updateEventStatus(tenantId, eventId, raw.version(), "PROCESSED", null, null,
                Instant.now(), orderId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "原始事件已被其他任务处理");
        }
        for (String type : List.of("MAPPING_FAILED", "CUSTOMER_UNRESOLVED", "PAYMENT_MISMATCH", "RETRY")) {
            repository.findIssueByEventAndType(tenantId, eventId, type).ifPresent(issue -> {
                if (!"RESOLVED".equals(issue.status())) {
                    repository.updateIssueStatus(tenantId, issue.id(), issue.version(), "RESOLVED", issue.attempts(),
                            null, null, Instant.now(), tenantId);
                }
            });
        }
        outboxService.append(tenantId, "youzan.order.processed.v1:" + raw.eventId(), "youzan.order.processed.v1",
                "youzan", Long.toString(orderId), Map.of("shopId", raw.shopId(),
                        "externalOrderNo", raw.externalOrderNo(), "orderId", orderId));
        auditService.record(tenantId, tenantId, "YOUZAN_ORDER_PROCESSED", "ORDER", Long.toString(orderId), null,
                Map.of("shopId", raw.shopId(), "externalOrderNo", raw.externalOrderNo(), "status", "PROCESSED"), null);
        YouzanEvent processed = repository.findEvent(tenantId, eventId).orElseThrow();
        return response(tenantId, processed, duplicate);
    }

    private YouzanEventResponse markFailure(long tenantId, long eventId, String type, String error) {
        YouzanEvent raw = repository.findEventForUpdate(tenantId, eventId).orElseThrow();
        String detail = safeError(error);
        if (repository.updateEventStatus(tenantId, eventId, raw.version(), type, detail, null,
                Instant.now(), raw.orderId()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "原始事件状态已变化");
        }
        YouzanSyncIssue existing = repository.findIssueByEventAndType(tenantId, eventId, type).orElse(null);
        if (existing == null) {
            repository.insertIssue(tenantId, new YouzanSyncIssue(null, eventId, type, "OPEN", detail, 1, detail,
                    null, null, null, null, null));
        } else {
            repository.updateIssueStatus(tenantId, existing.id(), existing.version(), "OPEN", existing.attempts() + 1,
                    detail, null, null, null);
        }
        outboxService.append(tenantId, "youzan.sync.issue.v1:" + raw.eventId() + ":" + type,
                "youzan.sync.issue.v1", "youzan", Long.toString(eventId),
                Map.of("eventId", raw.eventId(), "issueType", type, "status", "OPEN"));
        return response(tenantId, repository.findEvent(tenantId, eventId).orElseThrow(), false);
    }

    private YouzanEventResponse markRetry(long tenantId, long eventId, Exception error) {
        YouzanEvent raw = repository.findEventForUpdate(tenantId, eventId).orElseThrow();
        String detail = safeError(error.getMessage());
        Instant retryAt = Instant.now().plusSeconds(60);
        if (repository.updateEventStatus(tenantId, eventId, raw.version(), "RETRY", detail, retryAt,
                null, raw.orderId()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "原始事件状态已变化");
        }
        YouzanSyncIssue existing = repository.findIssueByEventAndType(tenantId, eventId, "RETRY").orElse(null);
        if (existing == null) {
            repository.insertIssue(tenantId, new YouzanSyncIssue(null, eventId, "RETRY", "OPEN", detail, 1, detail,
                    retryAt, null, null, null, null));
        } else {
            repository.updateIssueStatus(tenantId, existing.id(), existing.version(), "OPEN", existing.attempts() + 1,
                    detail, retryAt, null, null);
        }
        return response(tenantId, repository.findEvent(tenantId, eventId).orElseThrow(), false);
    }

    private YouzanEventResponse markDuplicate(long tenantId, long eventId) {
        YouzanEvent raw = repository.findEvent(tenantId, eventId).orElseThrow();
        if ("PROCESSED".equals(raw.processStatus())) {
            return response(tenantId, raw, true);
        }
        YouzanOrderEvent event = readEvent(raw.payloadCipher());
        YouzanOrderMap map = repository.findOrderMap(tenantId, event.shopId(), event.externalOrderNo()).orElseThrow();
        return complete(tenantId, eventId, raw, map.crmOrderId(), true);
    }

    private YouzanOrderEvent readEvent(String encrypted) {
        try {
            return objectMapper.readValue(cipher.decrypt(encrypted), YouzanOrderEvent.class);
        } catch (Exception ex) {
            throw new IllegalStateException("有赞原始事件解析失败", ex);
        }
    }

    private String maskedView(String normalized, String providerPayload) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode view =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(providerPayload);
            com.fasterxml.jackson.databind.node.ObjectNode internal =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(normalized);
            maskNode(view);
            view.setAll(internal);
            maskNode(view);
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞原始报文格式无效");
        }
    }

    private String mask(String rawBody) {
        try {
            JsonNode root = objectMapper.readTree(rawBody);
            maskNode(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞回调JSON格式无效");
        }
    }

    private void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode child = node.get(name);
                if (SENSITIVE_FIELDS.contains(name) && child.isValueNode()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(name, "***");
                } else {
                    maskNode(child);
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::maskNode);
        }
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "同步处理失败";
        }
        return error.length() > 1000 ? error.substring(0, 1000) : error;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "有赞事件无法序列化");
        }
    }

    private CurrentUser actor(long tenantId) {
        return currentUserService.loadFirstActiveByRole(tenantId, "ADMIN")
                .orElseThrow(() -> new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "当前租户未配置有赞系统操作员"));
    }

    private YouzanEventResponse response(long tenantId, YouzanEvent event, boolean duplicate) {
        Long orderId = event.orderId();
        if (orderId == null && duplicate) {
            orderId = repository.findOrderMap(tenantId, event.shopId(), event.externalOrderNo())
                    .map(YouzanOrderMap::crmOrderId).orElse(null);
        }
        return new YouzanEventResponse(event.id(), event.eventId(), event.eventType(), event.externalOrderNo(),
                event.processStatus(), event.processAttempts(), event.lastError(), orderId,
                event.receivedAt(), event.processedAt(), duplicate);
    }

    private static final class ProcessingFailure extends RuntimeException {
        private final String type;

        private ProcessingFailure(String type, String message) {
            super(message);
            this.type = type;
        }
    }
}
