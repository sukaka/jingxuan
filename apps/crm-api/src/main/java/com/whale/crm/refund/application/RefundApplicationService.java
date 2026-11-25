package com.whale.crm.refund.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTimelineCursor;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.refund.api.dto.CreateRefundItemRequest;
import com.whale.crm.refund.api.dto.CreateRefundRequest;
import com.whale.crm.refund.api.dto.ExecuteRefundRequest;
import com.whale.crm.refund.api.dto.ManualRefundSuccessRequest;
import com.whale.crm.refund.api.dto.RefundCallbackRequest;
import com.whale.crm.refund.api.dto.RefundDetail;
import com.whale.crm.refund.api.dto.ReviewRefundRequest;
import com.whale.crm.refund.domain.InFlightEntitlementCount;
import com.whale.crm.refund.domain.Refund;
import com.whale.crm.refund.domain.RefundItem;
import com.whale.crm.refund.domain.RefundPayment;
import com.whale.crm.refund.domain.RefundRepository;
import com.whale.crm.refund.domain.RefundStatusHistory;
import com.whale.crm.trading.application.FulfillmentTradingOperations;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

@Service
public class RefundApplicationService {
    private static final String CREATE_SCOPE = "refund.create";
    private static final String REVIEW_SCOPE = "refund.review";
    private static final String EXECUTE_SCOPE = "refund.execute";
    private static final String SUCCESS_SCOPE = "refund.success";
    private static final String CALLBACK_SCOPE = "refund.provider_callback";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final RefundRepository repository;
    private final TradingRepository tradingRepository;
    private final FulfillmentTradingOperations fulfillmentTradingOperations;
    private final CustomerScopeSupport scopeSupport;
    private final IdempotencyService idempotencyService;
    private final FileApplicationService fileOperations;
    private final OutboxService outboxService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Autowired
    public RefundApplicationService(RefundRepository repository, TradingRepository tradingRepository,
                                    FulfillmentTradingOperations fulfillmentTradingOperations,
                                    CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                                    FileApplicationService fileOperations, OutboxService outboxService,
                                    AuditService auditService, ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this(repository, tradingRepository, fulfillmentTradingOperations, scopeSupport, idempotencyService,
                fileOperations, outboxService, auditService, objectMapper, transactionManager,
                Clock.system(BUSINESS_ZONE));
    }

    RefundApplicationService(RefundRepository repository, TradingRepository tradingRepository,
                             FulfillmentTradingOperations fulfillmentTradingOperations,
                             CustomerScopeSupport scopeSupport, IdempotencyService idempotencyService,
                             FileApplicationService fileOperations, OutboxService outboxService,
                             AuditService auditService, ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.tradingRepository = tradingRepository;
        this.fulfillmentTradingOperations = fulfillmentTradingOperations;
        this.scopeSupport = scopeSupport;
        this.idempotencyService = idempotencyService;
        this.fileOperations = fileOperations;
        this.outboxService = outboxService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Refund> search(long tenantId, CurrentUser user, Long orderId, Long customerId,
                                       String status, int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.search(tenantId, orderId, customerId, status, query.scopeType(),
                        query.teamIds(), query.selfId(), offset, size),
                repository.countSearch(tenantId, orderId, customerId, status, query.scopeType(),
                        query.teamIds(), query.selfId()), page, size);
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timeline(long tenantId, CurrentUser user, long customerId,
                                                        CustomerTimelineCursor cursor) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        List<CustomerTimelineItemResponse> events = new ArrayList<>();
        int page = 1;
        long total;
        do {
            PageResponse<Refund> result = search(tenantId, user, null, customerId, null, page, 100);
            total = result.total();
            for (Refund refund : result.items()) {
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                details.put("status", refund.status());
                details.put("orderId", refund.orderId());
                details.put("requestedFen", refund.requestedFen());
                details.put("approvedFen", refund.approvedFen());
                details.put("succeededFen", refund.succeededFen());
                CustomerTimelineItemResponse event = CustomerTimelineItemResponse.of("REFUND", refund.id(), "REFUND",
                        refund.createdAt(), "退款 " + refund.refundNo(), "退款状态：" + refundStatusLabel(refund.status()), details,
                        refund.requestedBy());
                if (cursor == null || cursor.allows(event)) {
                    events.add(event);
                }
            }
            page++;
        } while ((long) (page - 1) * 100 < total);
        return events;
    }

    private static String refundStatusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "REQUESTED" -> "已申请";
            case "APPROVED" -> "已审核通过";
            case "REJECTED" -> "已驳回";
            case "PROCESSING" -> "退款处理中";
            case "SUCCEEDED" -> "退款成功";
            case "FAILED" -> "退款失败";
            case "CANCELED", "CANCELLED" -> "已取消";
            default -> "其他状态";
        };
    }

    @Transactional(readOnly = true)
    public RefundDetail detail(long tenantId, CurrentUser user, long refundId) {
        DataScope scope = scopeSupport.scope(user);
        Refund refund = repository.findScopedById(tenantId, refundId, scope.type().name(),
                        List.copyOf(scope.teamIds()), user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款单不存在或不可见"));
        return detailInternal(tenantId, refund.id());
    }

    public RefundDetail create(long tenantId, CurrentUser user, CreateRefundRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = canonical(CreateCanonical.from(request));
        long actorScopeUserId = actorUserId(user);
        IdempotencyRecord replay = lookup(tenantId, CREATE_SCOPE, actorScopeUserId, key, payload);
        if (replay != null) {
            return detail(tenantId, user, resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doCreate(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return replay(tenantId, user, CREATE_SCOPE, actorScopeUserId, key, payload, null);
        }
    }

    public RefundDetail review(long tenantId, CurrentUser user, long refundId,
                               ReviewRefundRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = canonical(new ReviewCanonical(refundId, request.version(), request.decision(),
                request.approvedFen(), request.reason()));
        return writeWithReplay(tenantId, user, REVIEW_SCOPE, key, payload, refundId,
                orderId -> doReview(tenantId, user, orderId, refundId, request, key, payload));
    }

    public RefundDetail execute(long tenantId, CurrentUser user, long refundId,
                                ExecuteRefundRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = canonical(new ExecuteCanonical(refundId, request.version(), request.provider(),
                request.externalRefundNo(), request.amountFen(), request.evidenceRef()));
        return writeWithReplay(tenantId, user, EXECUTE_SCOPE, key, payload, refundId,
                orderId -> doExecute(tenantId, user, orderId, refundId, request, key, payload));
    }

    public RefundDetail succeedManually(long tenantId, CurrentUser user, long refundId,
                                        ManualRefundSuccessRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String payload = canonical(new SuccessCanonical(refundId, request.externalRefundNo(), request.amountFen(),
                request.succeededAt().toString(), request.evidenceRef(), request.voucherFileId(), "MANUAL_REVIEW"));
        fileOperations.requireCleanUsable(tenantId, user, request.voucherFileId(), FileClassification.PAYMENT, null);
        return writeWithReplay(tenantId, user, SUCCESS_SCOPE, key, payload, refundId,
                orderId -> doSucceed(tenantId, user, orderId, refundId, null, request.externalRefundNo(),
                        request.amountFen(), request.succeededAt(), request.evidenceRef(), request.voucherFileId(),
                        "MANUAL_REVIEW", SUCCESS_SCOPE, key, payload));
    }

    public RefundDetail handleCallback(String provider, RefundCallbackRequest request, String idempotencyKey) {
        if (!"MOCK".equals(provider)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "真实退款通道尚未启用，当前仅支持模拟回调确认");
        }
        String key = requireKey(idempotencyKey);
        String payload = canonical(new CallbackCanonical(provider, request.externalRefundNo(), request.amountFen(),
                request.status(), request.occurredAt().toString(), request.evidenceRef()));
        List<RefundPayment> matches = repository.findPaymentsByExternal(provider, request.externalRefundNo());
        if (matches.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在");
        }
        if (matches.size() > 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "外部退款号跨租户冲突，无法唯一识别退款单");
        }
        RefundPayment payment = matches.get(0);
        long tenantId = payment.tenantId();
        long refundId = payment.refundId();
        IdempotencyRecord replay = lookup(tenantId, CALLBACK_SCOPE, 0L, key, payload);
        if (replay != null) {
            RefundDetail detail = detailInternal(tenantId, resourceId(replay));
            requireReplayRefund(detail, refundId);
            return detail;
        }
        long orderId = requireOrderId(tenantId, refundId);
        try {
            return transactionTemplate.execute(status -> {
                if ("SUCCEEDED".equals(request.status())) {
                    return doSucceed(tenantId, null, orderId, refundId, provider, request.externalRefundNo(),
                            request.amountFen(), request.occurredAt(), request.evidenceRef(), null,
                            "PROVIDER_CALLBACK", CALLBACK_SCOPE, key, payload);
                }
                return doFail(tenantId, orderId, refundId, provider, request, key, payload);
            });
        } catch (DuplicateKeyException ex) {
            return replay(tenantId, null, CALLBACK_SCOPE, 0L, key, payload, refundId);
        }
    }

    private RefundDetail doCreate(long tenantId, CurrentUser user, CreateRefundRequest request,
                                  String key, String payload) {
        Order order = tradingRepository.findOrderForUpdate(tenantId, request.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        scopeSupport.requireVisible(tenantId, order.customerId(), user);
        long actorScopeUserId = actorUserId(user);
        if (lookup(tenantId, CREATE_SCOPE, actorScopeUserId, key, payload) != null) {
            return replay(tenantId, user, CREATE_SCOPE, actorScopeUserId, key, payload, null);
        }
        if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已支付订单可以申请退款");
        }
        long refundable = Math.subtractExact(order.paidFen(), order.refundedFen());
        if (request.requestedFen() > refundable) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "申请金额超过订单可退金额");
        }
        validateAllocation(request.items(), request.requestedFen());
        List<Entitlement> orderEntitlements = tradingRepository.findEntitlementsByOrderIdsForUpdate(
                tenantId, List.of(order.id()));
        long inFlightOrderFen = repository.sumInFlightAmountByOrder(tenantId, order.id(), null);
        if (Math.addExact(inFlightOrderFen, request.requestedFen()) > refundable) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请金额超过订单剩余可退金额（含在途退款预占）");
        }
        Map<Long, Entitlement> entitlementsById = orderEntitlements.stream()
                .collect(Collectors.toMap(Entitlement::id, Function.identity()));
        Set<Long> orderItemIds = tradingRepository.findOrderItems(tenantId, order.id()).stream()
                .map(OrderItem::id).collect(Collectors.toSet());
        Map<Long, Long> inFlightCounts = toInFlightCountMap(
                repository.sumInFlightRevokeCountsByOrder(tenantId, order.id(), null));
        List<CreateRefundItemRequest> sortedRequestItems = sortedItems(request.items());
        for (CreateRefundItemRequest item : sortedRequestItems) {
            Entitlement entitlement = entitlementsById.get(item.entitlementId());
            if (entitlement == null) {
                if (tradingRepository.findEntitlement(tenantId, item.entitlementId()).isEmpty()) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退款权益批次不存在");
                }
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退款权益批次不属于该订单或客户");
            }
            if (!orderItemIds.contains(entitlement.orderItemId()) || !order.customerId().equals(entitlement.customerId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "退款权益批次不属于该订单或客户");
            }
            if (item.revokeCount() > entitlement.availableCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "申请回收次数超过权益可用余额");
            }
            long reservedCount = inFlightCounts.getOrDefault(entitlement.id(), 0L);
            long remainingCount = Math.max(0L, entitlement.availableCount() - reservedCount);
            if (item.revokeCount() > remainingCount) {
                throw new BusinessException(ErrorCode.CONFLICT, "申请回收次数超过权益可用余额（含在途退款预占）");
            }
        }
        String refundNo = "RF" + UlidCreator.getUlid().toString();
        repository.insertRefund(tenantId, new Refund(null, tenantId, refundNo, order.id(), order.customerId(),
                request.requestedFen(), 0L, 0L, request.reasonCode(), request.reasonDetail(), "REQUESTED",
                user.userId(), null, null, null, null, null, null));
        Refund created = repository.findByNo(tenantId, refundNo).orElseThrow();
        for (CreateRefundItemRequest item : sortedRequestItems) {
            repository.insertItem(tenantId, new RefundItem(null, tenantId, created.id(), item.entitlementId(),
                    item.revokeCount(), item.allocatedFen()));
        }
        insertHistory(tenantId, created.id(), "NONE", "REQUESTED", user.userId(), request.reasonDetail());
        RefundDetail after = detailInternal(tenantId, created.id());
        auditService.record(tenantId, user.userId(), "REFUND_REQUEST", "REFUND", created.id().toString(),
                order, after, request.reasonDetail());
        recordSuccess(tenantId, CREATE_SCOPE, actorScopeUserId, key, created.id(), payload);
        return after;
    }

    private RefundDetail doReview(long tenantId, CurrentUser user, long orderId, long refundId,
                                  ReviewRefundRequest request, String key, String payload) {
        Order order = tradingRepository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原订单不存在"));
        boolean approving = "APPROVED".equals(request.decision());
        List<Entitlement> orderEntitlements = approving
                ? tradingRepository.findEntitlementsByOrderIdsForUpdate(tenantId, List.of(order.id()))
                : List.of();
        Refund before = lockVisible(tenantId, user, refundId);
        if (!before.orderId().equals(order.id())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单关联订单不一致");
        }
        long actorScopeUserId = actorUserId(user);
        if (lookup(tenantId, REVIEW_SCOPE, actorScopeUserId, key, payload) != null) {
            return detailInternal(tenantId, refundId);
        }
        if (!"REQUESTED".equals(before.status()) || !before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单状态或版本已变化，只有待审批单可审批");
        }
        if (before.requestedBy().equals(user.userId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "退款申请人与审批人必须不同");
        }
        long approvedFen = 0;
        if (approving) {
            if (request.approvedFen() == null || request.approvedFen() <= 0
                    || request.approvedFen() > before.requestedFen()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "批准金额必须为正且不得超过申请金额");
            }
            approvedFen = request.approvedFen();
            if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "只有已支付订单可批准退款");
            }
            long refundable = Math.subtractExact(order.paidFen(), order.refundedFen());
            long otherInFlightOrderFen = repository.sumInFlightAmountByOrder(tenantId, order.id(), refundId);
            if (Math.addExact(otherInFlightOrderFen, approvedFen) > refundable) {
                throw new BusinessException(ErrorCode.CONFLICT, "批准金额超过订单剩余可退金额（含在途退款预占）");
            }
            List<RefundItem> items = sortedRefundItems(repository.findItems(tenantId, refundId));
            if (items.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFLICT, "退款单缺少权益明细");
            }
            validateOrderEntitlementCapacities(tenantId, order, orderEntitlements, items, refundId,
                    "批准回收次数超过权益可用余额（含在途退款预占）");
            reallocateItemsForApproval(tenantId, before, items, approvedFen);
        } else if (request.approvedFen() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回退款不能填写批准金额");
        }
        if (repository.updateStatus(tenantId, refundId, before.version(), "REQUESTED", request.decision(),
                approvedFen, null, user.userId(), null, null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款审批版本冲突，请重试");
        }
        insertHistory(tenantId, refundId, "REQUESTED", request.decision(), user.userId(), request.reason());
        RefundDetail after = detailInternal(tenantId, refundId);
        auditService.record(tenantId, user.userId(), "REFUND_REVIEW", "REFUND", Long.toString(refundId),
                before, after, request.reason());
        recordSuccess(tenantId, REVIEW_SCOPE, actorScopeUserId, key, refundId, payload);
        return after;
    }

    private RefundDetail doExecute(long tenantId, CurrentUser user, long orderId, long refundId,
                                   ExecuteRefundRequest request, String key, String payload) {
        Order order = tradingRepository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原订单不存在"));
        List<Entitlement> orderEntitlements = tradingRepository.findEntitlementsByOrderIdsForUpdate(
                tenantId, List.of(order.id()));
        Refund before = lockVisible(tenantId, user, refundId);
        if (!before.orderId().equals(order.id())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单关联订单不一致");
        }
        long actorScopeUserId = actorUserId(user);
        if (lookup(tenantId, EXECUTE_SCOPE, actorScopeUserId, key, payload) != null) {
            return detailInternal(tenantId, refundId);
        }
        if (!List.of("APPROVED", "FAILED").contains(before.status()) || !before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已批准或失败重试的退款单可进入执行中");
        }
        if (!request.amountFen().equals(before.approvedFen())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "执行金额必须与批准金额一致");
        }
        if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已支付订单可执行退款");
        }
        long refundable = Math.subtractExact(order.paidFen(), order.refundedFen());
        long otherInFlightOrderFen = repository.sumInFlightAmountByOrder(tenantId, order.id(), refundId);
        if (Math.addExact(otherInFlightOrderFen, before.approvedFen()) > refundable) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行金额超过订单剩余可退金额（含在途退款预占）");
        }
        List<RefundItem> items = sortedRefundItems(repository.findItems(tenantId, refundId));
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单缺少权益明细");
        }
        validatePersistedAllocation(tenantId, refundId, before.approvedFen());
        validateOrderEntitlementCapacities(tenantId, order, orderEntitlements, items, refundId,
                "执行回收次数超过权益可用余额（含在途退款预占）");
        repository.findPaymentByExternal(tenantId, request.provider(), request.externalRefundNo())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.CONFLICT, "外部退款号已被使用");
                });
        repository.insertPayment(tenantId, new RefundPayment(null, tenantId, refundId, request.provider(),
                request.externalRefundNo(), request.amountFen(), "PENDING",
                "MOCK".equals(request.provider()) ? "PROVIDER_CALLBACK" : "MANUAL_REVIEW",
                normalizeBlankToNull(request.evidenceRef()), null, null, null, null, null));
        if (repository.updateStatus(tenantId, refundId, before.version(), before.status(), "PROCESSING",
                null, null, null, user.userId(), null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款执行版本冲突，请重试");
        }
        insertHistory(tenantId, refundId, before.status(), "PROCESSING", user.userId(), request.externalRefundNo());
        RefundDetail after = detailInternal(tenantId, refundId);
        auditService.record(tenantId, user.userId(), "REFUND_EXECUTE", "REFUND", Long.toString(refundId),
                before, after, request.externalRefundNo());
        recordSuccess(tenantId, EXECUTE_SCOPE, actorScopeUserId, key, refundId, payload);
        return after;
    }

    private RefundDetail doSucceed(long tenantId, CurrentUser user, long orderId, long refundId,
                                   String expectedProvider, String externalRefundNo, long amountFen, Instant succeededAt,
                                   String evidenceRef, Long voucherFileId, String verificationMethod,
                                   String scope, String key, String payload) {
        Order order = tradingRepository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原订单不存在"));
        List<Entitlement> orderEntitlements = tradingRepository.findEntitlementsByOrderIdsForUpdate(
                tenantId, List.of(order.id()));
        Refund before = user == null ? lockRefund(tenantId, refundId) : lockVisible(tenantId, user, refundId);
        if (!before.orderId().equals(order.id())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单关联订单不一致");
        }
        long actorScopeUserId = actorUserId(user);
        if (lookup(tenantId, scope, actorScopeUserId, key, payload) != null) {
            return detailInternal(tenantId, refundId);
        }
        String provider = expectedProvider != null
                ? expectedProvider
                : paymentProvider(tenantId, refundId, externalRefundNo);
        RefundPayment foundPayment = repository.findPaymentByExternal(tenantId, provider, externalRefundNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在"));
        RefundPayment payment = repository.findPaymentForUpdate(tenantId, foundPayment.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在"));
        if (!payment.refundId().equals(refundId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "外部退款号不属于该退款单");
        }
        if ("MANUAL_REVIEW".equals(verificationMethod) && "MOCK".equals(payment.provider())) {
            throw new BusinessException(ErrorCode.CONFLICT, "模拟退款必须通过验签回调确认，不能人工标记成功");
        }
        if ("PROVIDER_CALLBACK".equals(verificationMethod) && !"MOCK".equals(payment.provider())) {
            throw new BusinessException(ErrorCode.CONFLICT, "非模拟退款渠道不接受模拟回调确认");
        }
        if (!payment.amountFen().equals(amountFen) || !before.approvedFen().equals(amountFen)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "成功金额必须与执行及批准金额一致");
        }
        if ("SUCCEEDED".equals(payment.status()) && "SUCCEEDED".equals(before.status())) {
            recordSuccess(tenantId, scope, actorScopeUserId, key, refundId, payload);
            return detailInternal(tenantId, refundId);
        }
        if (!"PROCESSING".equals(before.status()) || !"PENDING".equals(payment.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有执行中的待确认退款可确认成功");
        }
        if (!order.customerId().equals(before.customerId())
                || !List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单快照与退款申请不一致");
        }
        validatePersistedAllocation(tenantId, refundId, amountFen);
        List<RefundItem> items = sortedRefundItems(repository.findItems(tenantId, refundId));
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单缺少权益明细");
        }
        Map<Long, Entitlement> entitlementsById = orderEntitlements.stream()
                .collect(Collectors.toMap(Entitlement::id, Function.identity()));
        Set<Long> orderItemIds = tradingRepository.findOrderItems(tenantId, order.id()).stream()
                .map(OrderItem::id).collect(Collectors.toSet());
        Long actorId = user == null ? null : user.userId();
        LocalDate today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
        for (RefundItem item : items) {
            Entitlement entitlement = entitlementsById.get(item.entitlementId());
            if (entitlement == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "退款权益批次不存在");
            }
            if (!orderItemIds.contains(entitlement.orderItemId())
                    || !order.customerId().equals(entitlement.customerId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益批次已不再属于原订单或客户，退款全部回滚");
            }
            if (item.revokeCount() <= 0 || item.revokeCount() > entitlement.availableCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "回收次数超过当前权益余额，退款全部回滚");
            }
            int newBalance = entitlement.availableCount() - item.revokeCount();
            boolean expired = "EXPIRED".equals(entitlement.status())
                    || (entitlement.validTo() != null && entitlement.validTo().isBefore(today));
            String newStatus;
            if (newBalance == 0) {
                newStatus = "REVOKED";
            } else if (expired) {
                newStatus = "EXPIRED";
            } else if ("PENDING".equals(entitlement.status())) {
                newStatus = "PENDING";
            } else {
                newStatus = "AVAILABLE";
            }
            if (tradingRepository.updateEntitlementBalance(tenantId, entitlement.id(), entitlement.version(),
                    newBalance, newStatus, entitlement.versionNo() + 1) == 0) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益版本冲突，退款全部回滚");
            }
            tradingRepository.insertLedger(tenantId, new EntitlementLedgerEntry(null, entitlement.id(), "REFUND_REVOKE",
                    -item.revokeCount(), "REFUND", Long.toString(refundId),
                    "refund.revoke:" + refundId + ":" + entitlement.id(), actorId,
                    "退款成功回收权益", null, null));
            if (tradingRepository.sumLedgerForUpdate(tenantId, entitlement.id()) != newBalance) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益账本与余额不一致，退款全部回滚");
            }
        }
        if (tradingRepository.addRefundedFen(tenantId, order.id(), order.version(), amountFen) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单退款累计超过实付金额或版本冲突，退款全部回滚");
        }
        Order refreshedOrder = tradingRepository.findOrderForUpdate(tenantId, order.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原订单不存在"));
        fulfillmentTradingOperations.recalculateOrderStatusAfterRefund(tenantId, actorId, refreshedOrder, refundId);
        String normalizedEvidence = normalizeBlankToNull(evidenceRef);
        if (repository.updatePaymentStatus(tenantId, payment.id(), payment.version(), "PENDING", "SUCCEEDED",
                succeededAt, normalizedEvidence, voucherFileId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款支付记录版本冲突");
        }
        if (repository.updateStatus(tenantId, refundId, before.version(), "PROCESSING", "SUCCEEDED",
                null, amountFen, null, null, succeededAt) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款主单版本冲突");
        }
        insertHistory(tenantId, refundId, "PROCESSING", "SUCCEEDED", actorId, evidenceRef);
        for (RefundItem item : items) {
            outboxService.append(tenantId, "entitlement.refund_revoked.v1:" + refundId + ":" + item.entitlementId(),
                    "entitlement.refund_revoked.v1", "entitlement", item.entitlementId().toString(),
                    Map.of("refundId", refundId, "entitlementId", item.entitlementId(), "count", item.revokeCount(),
                            "allocatedFen", item.allocatedFen(), "externalRefundNo", externalRefundNo));
        }
        outboxService.append(tenantId, "refund.succeeded.v1:" + refundId, "refund.succeeded.v1", "refund",
                Long.toString(refundId), Map.of("refundId", refundId, "orderId", order.id(),
                        "customerId", before.customerId(), "amountFen", amountFen,
                        "externalRefundNo", externalRefundNo, "verificationMethod", verificationMethod));
        RefundDetail after = detailInternal(tenantId, refundId);
        auditService.record(tenantId, actorId, "REFUND_SUCCEED", "REFUND", Long.toString(refundId),
                before, after, evidenceRef);
        recordSuccess(tenantId, scope, actorScopeUserId, key, refundId, payload);
        return after;
    }

    private RefundDetail doFail(long tenantId, long orderId, long refundId, String provider,
                                RefundCallbackRequest request, String key, String payload) {
        Order order = tradingRepository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原订单不存在"));
        Refund before = lockRefund(tenantId, refundId);
        if (!before.orderId().equals(order.id())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款单关联订单不一致");
        }
        if (lookup(tenantId, CALLBACK_SCOPE, 0L, key, payload) != null) {
            return detailInternal(tenantId, refundId);
        }
        RefundPayment foundPayment = repository.findPaymentByExternal(tenantId, provider, request.externalRefundNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在"));
        RefundPayment payment = repository.findPaymentForUpdate(tenantId, foundPayment.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在"));
        if (!payment.refundId().equals(refundId) || !payment.amountFen().equals(request.amountFen())
                || !before.approvedFen().equals(request.amountFen())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "失败回执的退款单或金额不一致");
        }
        if ("FAILED".equals(payment.status()) && "FAILED".equals(before.status())) {
            recordSuccess(tenantId, CALLBACK_SCOPE, 0L, key, refundId, payload);
            return detailInternal(tenantId, refundId);
        }
        if (!"PROCESSING".equals(before.status()) || !"PENDING".equals(payment.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有执行中的待确认退款可确认失败");
        }
        String normalizedEvidence = normalizeBlankToNull(request.evidenceRef());
        if (repository.updatePaymentStatus(tenantId, payment.id(), payment.version(), "PENDING", "FAILED",
                null, normalizedEvidence, null) == 0
                || repository.updateStatus(tenantId, refundId, before.version(), "PROCESSING", "FAILED",
                null, null, null, null, null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款失败回执版本冲突");
        }
        insertHistory(tenantId, refundId, "PROCESSING", "FAILED", null, request.evidenceRef());
        RefundDetail after = detailInternal(tenantId, refundId);
        auditService.record(tenantId, null, "REFUND_FAIL", "REFUND", Long.toString(refundId),
                before, after, request.evidenceRef());
        recordSuccess(tenantId, CALLBACK_SCOPE, 0L, key, refundId, payload);
        return after;
    }

    private long requireOrderId(long tenantId, long refundId) {
        return repository.findById(tenantId, refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款单不存在"))
                .orderId();
    }

    private void validateOrderEntitlementCapacities(long tenantId, Order order, List<Entitlement> orderEntitlements,
                                                    List<RefundItem> items, long refundId, String conflictMessage) {
        Map<Long, Entitlement> entitlementsById = orderEntitlements.stream()
                .collect(Collectors.toMap(Entitlement::id, Function.identity()));
        Set<Long> orderItemIds = tradingRepository.findOrderItems(tenantId, order.id()).stream()
                .map(OrderItem::id).collect(Collectors.toSet());
        Map<Long, Long> otherInFlightCounts = toInFlightCountMap(
                repository.sumInFlightRevokeCountsByOrder(tenantId, order.id(), refundId));
        for (RefundItem item : items) {
            Entitlement entitlement = entitlementsById.get(item.entitlementId());
            if (entitlement == null || !orderItemIds.contains(entitlement.orderItemId())
                    || !order.customerId().equals(entitlement.customerId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "退款权益批次与原订单或客户不一致");
            }
            long reservedCount = otherInFlightCounts.getOrDefault(entitlement.id(), 0L);
            long remainingCount = Math.max(0L, entitlement.availableCount() - reservedCount);
            if (item.revokeCount() <= 0 || item.revokeCount() > remainingCount) {
                throw new BusinessException(ErrorCode.CONFLICT, conflictMessage);
            }
        }
    }

    private void reallocateItemsForApproval(long tenantId, Refund before, List<RefundItem> items, long approvedFen) {
        long requestedSum = items.stream().mapToLong(RefundItem::allocatedFen).sum();
        if (requestedSum != before.requestedFen()) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款现金分摊与申请金额不一致");
        }
        if (approvedFen < before.requestedFen()) {
            long[] newAllocations = proportionalAllocate(approvedFen, items, before.requestedFen());
            for (int i = 0; i < items.size(); i++) {
                RefundItem item = items.get(i);
                if (newAllocations[i] != item.allocatedFen()) {
                    if (repository.updateItemAllocatedFen(tenantId, item.id(), before.id(), newAllocations[i]) == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "退款分摊更新失败");
                    }
                }
            }
        }
        validatePersistedAllocation(tenantId, before.id(), approvedFen);
    }

    private long[] proportionalAllocate(long approvedFen, List<RefundItem> items, long totalRequestedFen) {
        long[] result = new long[items.size()];
        BigDecimal[] remainders = new BigDecimal[items.size()];
        List<Integer> positiveIndices = new ArrayList<>();
        long floorSum = 0;
        for (int i = 0; i < items.size(); i++) {
            long weight = items.get(i).allocatedFen();
            if (weight <= 0) {
                result[i] = 0L;
                remainders[i] = BigDecimal.ZERO;
                continue;
            }
            BigDecimal raw = BigDecimal.valueOf(approvedFen).multiply(BigDecimal.valueOf(weight))
                    .divide(BigDecimal.valueOf(totalRequestedFen), 20, RoundingMode.DOWN);
            result[i] = raw.longValue();
            floorSum = Math.addExact(floorSum, result[i]);
            remainders[i] = raw.subtract(BigDecimal.valueOf(result[i]));
            positiveIndices.add(i);
        }
        long remaining = Math.subtractExact(approvedFen, floorSum);
        positiveIndices.sort(Comparator.<Integer, BigDecimal>comparing(idx -> remainders[idx]).reversed()
                .thenComparingInt(idx -> idx));
        for (long i = 0; i < remaining; i++) {
            int idx = positiveIndices.get((int) (i % positiveIndices.size()));
            result[idx]++;
        }
        return result;
    }

    private Map<Long, Long> toInFlightCountMap(List<InFlightEntitlementCount> counts) {
        Map<Long, Long> map = new HashMap<>();
        for (InFlightEntitlementCount count : counts) {
            map.put(count.entitlementId(), count.revokeCount());
        }
        return map;
    }

    private Refund lockVisible(long tenantId, CurrentUser user, long refundId) {
        Refund refund = lockRefund(tenantId, refundId);
        scopeSupport.requireVisible(tenantId, refund.customerId(), user);
        return refund;
    }

    private Refund lockRefund(long tenantId, long refundId) {
        return repository.findForUpdate(tenantId, refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款单不存在"));
    }

    private String paymentProvider(long tenantId, long refundId, String externalRefundNo) {
        List<RefundPayment> matches = repository.findPayments(tenantId, refundId).stream()
                .filter(payment -> payment.externalRefundNo().equals(externalRefundNo))
                .toList();
        return matches.stream()
                .filter(payment -> "PENDING".equals(payment.status()))
                .findFirst()
                .or(() -> matches.stream().filter(payment -> "SUCCEEDED".equals(payment.status())).findFirst())
                .or(() -> matches.stream().findFirst())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款执行记录不存在"))
                .provider();
    }

    private void validatePersistedAllocation(long tenantId, long refundId, long amountFen) {
        long allocated = repository.findItems(tenantId, refundId).stream()
                .mapToLong(RefundItem::allocatedFen).sum();
        if (allocated != amountFen) {
            throw new BusinessException(ErrorCode.CONFLICT, "退款现金分摊与批准金额不一致");
        }
    }

    private void validateAllocation(List<CreateRefundItemRequest> items, long amountFen) {
        Set<Long> ids = new HashSet<>();
        long allocated = 0;
        for (CreateRefundItemRequest item : items) {
            if (!ids.add(item.entitlementId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一权益批次不能重复分摊");
            }
            allocated = Math.addExact(allocated, item.allocatedFen());
        }
        if (allocated != amountFen) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "各权益现金分摊之和必须等于申请金额");
        }
    }

    private List<CreateRefundItemRequest> sortedItems(List<CreateRefundItemRequest> items) {
        return items.stream().sorted(Comparator.comparingLong(CreateRefundItemRequest::entitlementId)).toList();
    }

    private List<RefundItem> sortedRefundItems(List<RefundItem> items) {
        return items.stream().sorted(Comparator.comparingLong(RefundItem::entitlementId)).toList();
    }

    private String normalizeBlankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long actorUserId(CurrentUser user) {
        return user == null ? 0L : user.userId();
    }

    private RefundDetail writeWithReplay(long tenantId, CurrentUser user, String scope, String key, String payload,
                                         long refundId, LongFunction<RefundDetail> action) {
        long actorScopeUserId = actorUserId(user);
        IdempotencyRecord replay = lookup(tenantId, scope, actorScopeUserId, key, payload);
        if (replay != null) {
            RefundDetail detail = detail(tenantId, user, resourceId(replay));
            requireReplayRefund(detail, refundId);
            return detail;
        }
        long orderId = requireOrderId(tenantId, refundId);
        try {
            return transactionTemplate.execute(status -> action.apply(orderId));
        } catch (DuplicateKeyException ex) {
            return replay(tenantId, user, scope, actorScopeUserId, key, payload, refundId);
        }
    }

    private RefundDetail replay(long tenantId, CurrentUser user, String scope, long actorScopeUserId,
                                String key, String payload, Long expectedRefundId) {
        return transactionTemplate.execute(status -> {
            IdempotencyRecord record = lookup(tenantId, scope, actorScopeUserId, key, payload);
            if (record == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "退款请求冲突且没有已提交幂等结果");
            }
            RefundDetail detail = user == null ? detailInternal(tenantId, resourceId(record))
                    : detail(tenantId, user, resourceId(record));
            if (expectedRefundId != null) {
                requireReplayRefund(detail, expectedRefundId);
            }
            return detail;
        });
    }

    private void requireReplayRefund(RefundDetail detail, long expectedRefundId) {
        if (!detail.refund().id().equals(expectedRefundId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该退款单");
        }
    }

    private IdempotencyRecord lookup(long tenantId, String scope, long actorScopeUserId, String key, String payload) {
        return idempotencyService.lookup(tenantId, scope, actorScopeUserId, key, payload).orElse(null);
    }

    private void recordSuccess(long tenantId, String scope, long actorScopeUserId, String key,
                               long refundId, String payload) {
        idempotencyService.recordSuccess(tenantId, scope, actorScopeUserId, key, "REFUND",
                Long.toString(refundId), payload);
    }

    private long resourceId(IdempotencyRecord record) {
        return Long.parseLong(record.getResourceId());
    }

    private String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "写请求必须提供1至128字符的幂等键");
        }
        return idempotencyKey.strip();
    }

    private String canonical(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("退款请求规范化失败", ex);
        }
    }

    private void insertHistory(long tenantId, long refundId, String from, String to, Long actorId, String reason) {
        repository.insertHistory(tenantId, new RefundStatusHistory(null, tenantId, refundId, from, to, actorId, reason, null));
    }

    private RefundDetail detailInternal(long tenantId, long refundId) {
        Refund refund = repository.findById(tenantId, refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "退款单不存在"));
        return new RefundDetail(refund, repository.findItems(tenantId, refundId),
                repository.findPayments(tenantId, refundId), repository.findHistory(tenantId, refundId));
    }

    private record ItemCanonical(long entitlementId, int revokeCount, long allocatedFen) {
        static ItemCanonical from(CreateRefundItemRequest item) {
            return new ItemCanonical(item.entitlementId(), item.revokeCount(), item.allocatedFen());
        }
    }

    private record CreateCanonical(long orderId, long requestedFen, String reasonCode, String reasonDetail,
                                   List<ItemCanonical> items) {
        static CreateCanonical from(CreateRefundRequest request) {
            return new CreateCanonical(request.orderId(), request.requestedFen(), request.reasonCode(),
                    request.reasonDetail(), request.items().stream().map(ItemCanonical::from)
                    .sorted(Comparator.comparingLong(ItemCanonical::entitlementId)).toList());
        }
    }

    private record ReviewCanonical(long refundId, long version, String decision, Long approvedFen, String reason) { }
    private record ExecuteCanonical(long refundId, long version, String provider, String externalRefundNo,
                                    long amountFen, String evidenceRef) { }
    private record SuccessCanonical(long refundId, String externalRefundNo, long amountFen, String succeededAt,
                                    String evidenceRef, Long voucherFileId, String verificationMethod) { }
    private record CallbackCanonical(String provider, String externalRefundNo, long amountFen, String status,
                                     String occurredAt, String evidenceRef) { }
}
