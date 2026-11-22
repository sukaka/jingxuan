package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.EntitlementCorrectionView;
import com.whale.crm.trading.domain.EntitlementLedgerEntry;
import com.whale.crm.trading.domain.FulfillmentEntitlementView;
import com.whale.crm.trading.domain.LockedEntitlementBatch;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TradingFulfillmentApplicationService implements FulfillmentTradingOperations {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TradingRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TradingFulfillmentApplicationService(TradingRepository repository, CustomerScopeSupport scopeSupport,
                                                OutboxService outboxService, ObjectMapper objectMapper) {
        this(repository, scopeSupport, outboxService, objectMapper, Clock.system(BUSINESS_ZONE));
    }

    TradingFulfillmentApplicationService(TradingRepository repository, CustomerScopeSupport scopeSupport,
                                         OutboxService outboxService, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentEntitlementView> appointmentOptions(long tenantId, CurrentUser user, long customerId) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        LocalDate today = today();
        return repository.searchEntitlements(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        customerId, "AVAILABLE", 0, 100).stream()
                .map(entitlement -> snapshot(tenantId, entitlement))
                .filter(view -> view.entitlement().availableCount() > 0)
                .filter(view -> view.entitlement().validFrom() == null || !view.entitlement().validFrom().isAfter(today))
                .filter(view -> view.entitlement().validTo() == null || !view.entitlement().validTo().isBefore(today))
                .filter(view -> !view.institutionIds().isEmpty())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentEntitlementView> appointmentOptionsForInstitution(long tenantId, long customerId,
                                                                             long institutionId,
                                                                             List<Long> entitlementIds) {
        LocalDate today = today();
        return entitlementIds.stream().distinct().sorted()
                .flatMap(entitlementId -> repository.findEntitlement(tenantId, entitlementId).stream())
                .filter(entitlement -> entitlement.customerId().equals(customerId))
                .map(entitlement -> snapshot(tenantId, entitlement))
                .filter(view -> view.institutionIds().contains(institutionId))
                .filter(view -> view.entitlement().availableCount() > 0)
                .filter(view -> view.entitlement().validFrom() == null || !view.entitlement().validFrom().isAfter(today))
                .filter(view -> view.entitlement().validTo() == null || !view.entitlement().validTo().isBefore(today))
                .toList();
    }

    @Override
    public LockedEntitlementBatch requireAppointmentEntitlements(long tenantId,
                                                                 List<AppointmentLockCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new LockedEntitlementBatch(Map.of(), Map.of());
        }
        LockedEntitlementBatch batch = lockEntitlementBatch(tenantId,
                commands.stream().map(AppointmentLockCommand::entitlementId).toList());
        for (AppointmentLockCommand command : commands) {
            FulfillmentEntitlementView view = requireLockedView(batch, command.entitlementId());
            validateUse(view, command.customerId(), command.institutionId(),
                    view.entitlement().projectId(), command.count(), command.scheduledAt());
        }
        return batch;
    }

    @Override
    public FulfillmentEntitlementView requireAppointmentEntitlement(long tenantId, long entitlementId, long customerId,
                                                                    long institutionId, int count, Instant scheduledAt) {
        LockedEntitlementBatch batch = requireAppointmentEntitlements(tenantId,
                List.of(new AppointmentLockCommand(entitlementId, customerId, institutionId, count, scheduledAt)));
        return requireLockedView(batch, entitlementId);
    }

    @Override
    public LockedEntitlementBatch lockRedeemables(long tenantId, List<RedemptionLockCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new LockedEntitlementBatch(Map.of(), Map.of());
        }
        LockedEntitlementBatch batch = lockEntitlementBatch(tenantId,
                commands.stream().map(RedemptionLockCommand::entitlementId).toList());
        for (RedemptionLockCommand command : commands) {
            FulfillmentEntitlementView view = requireLockedView(batch, command.entitlementId());
            validateUse(view, command.customerId(), command.institutionId(),
                    command.projectId(), command.count(), command.serviceAt());
        }
        return batch;
    }

    @Override
    public FulfillmentEntitlementView lockRedeemable(long tenantId, long entitlementId, long customerId,
                                                     long institutionId, long projectId, int count, Instant serviceAt) {
        LockedEntitlementBatch batch = lockRedeemables(tenantId,
                List.of(new RedemptionLockCommand(entitlementId, customerId, institutionId, projectId, count, serviceAt)));
        return requireLockedView(batch, entitlementId);
    }

    @Override
    public LockedEntitlementBatch lockReversibles(long tenantId, List<ReversalLockCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new LockedEntitlementBatch(Map.of(), Map.of());
        }
        LockedEntitlementBatch batch = lockEntitlementBatch(tenantId,
                commands.stream().map(ReversalLockCommand::entitlementId).toList());
        LocalDate today = today();
        for (ReversalLockCommand command : commands) {
            FulfillmentEntitlementView view = requireLockedView(batch, command.entitlementId());
            Entitlement entitlement = view.entitlement();
            validatePaidSnapshot(view, command.customerId(), command.projectId());
            if (!List.of("AVAILABLE", "EXHAUSTED").contains(entitlement.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益当前状态不允许直接冲正恢复");
            }
            if (entitlement.validTo() != null && entitlement.validTo().isBefore(today)) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益已过期，不能通过冲正悄悄恢复可用次数");
            }
            validateOriginalRedemption(tenantId, command.entitlementId(),
                    command.originalSourceRef(), command.originalCount());
        }
        return batch;
    }

    @Override
    public FulfillmentEntitlementView lockReversible(long tenantId, long entitlementId, long customerId, long projectId,
                                                     String originalSourceRef, int originalCount) {
        LockedEntitlementBatch batch = lockReversibles(tenantId,
                List.of(new ReversalLockCommand(entitlementId, customerId, projectId,
                        originalSourceRef, originalCount)));
        return requireLockedView(batch, entitlementId);
    }

    @Override
    public List<Entitlement> appendRedemptions(long tenantId, long actorUserId, LockedEntitlementBatch locked,
                                               List<RedemptionAppendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<Entitlement> updated = new ArrayList<>();
        Map<Long, String> orderSourceRefs = new TreeMap<>();
        for (RedemptionAppendCommand command : commands) {
            FulfillmentEntitlementView view = requireLockedView(locked, command.entitlementId());
            Entitlement current = view.entitlement();
            EntitlementLedgerEntry existing = repository.findLedgerByKey(tenantId, command.idempotencyKey()).orElse(null);
            if (existing != null) {
                validateLedger(existing, current.id(), "REDEEM", -command.count(), command.sourceRef());
                updated.add(repository.findEntitlement(tenantId, current.id()).orElseThrow());
            } else {
                int newBalance = current.availableCount() - command.count();
                if (command.count() <= 0 || newBalance < 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "权益余额不足，不能出现负数余额");
                }
                String newStatus = newBalance == 0 ? "EXHAUSTED" : "AVAILABLE";
                updateBalance(tenantId, current, newBalance, newStatus);
                repository.insertLedger(tenantId, new EntitlementLedgerEntry(null, current.id(), "REDEEM",
                        -command.count(), "REDEMPTION", command.sourceRef(), command.idempotencyKey(),
                        actorUserId, "机构预约正式核销", null, null));
                assertLedgerBalance(tenantId, current.id(), newBalance);
                outboxService.append(tenantId, "entitlement.redeemed.v1:" + command.idempotencyKey(),
                        "entitlement.redeemed.v1", "entitlement", Long.toString(current.id()),
                        Map.of("entitlementId", current.id(), "count", command.count(),
                                "sourceRef", command.sourceRef()));
                updated.add(repository.findEntitlement(tenantId, current.id()).orElseThrow());
            }
            orderSourceRefs.putIfAbsent(view.orderId(), command.sourceRef());
        }
        for (Map.Entry<Long, String> entry : orderSourceRefs.entrySet()) {
            Order lockedOrder = locked.ordersById().get(entry.getKey());
            if (lockedOrder == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
            }
            recalculateOrderStatus(tenantId, actorUserId, lockedOrder, entry.getValue(), "REDEMPTION_POSTED");
        }
        return List.copyOf(updated);
    }

    @Override
    public List<Entitlement> appendReversals(long tenantId, long actorUserId, LockedEntitlementBatch locked,
                                             List<ReversalAppendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<Entitlement> updated = new ArrayList<>();
        Map<Long, String> orderSourceRefs = new TreeMap<>();
        for (ReversalAppendCommand command : commands) {
            FulfillmentEntitlementView view = requireLockedView(locked, command.entitlementId());
            Entitlement current = view.entitlement();
            EntitlementLedgerEntry existing = repository.findLedgerByKey(tenantId, command.idempotencyKey()).orElse(null);
            if (existing != null) {
                validateLedger(existing, current.id(), "CORRECTION_IN", command.count(), command.sourceRef());
                updated.add(repository.findEntitlement(tenantId, current.id()).orElseThrow());
            } else {
                if (command.count() <= 0) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "冲正次数必须为正整数");
                }
                int newBalance = Math.addExact(current.availableCount(), command.count());
                if (newBalance > current.grantedCount()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "冲正恢复后的余额不能超过权益发放次数");
                }
                updateBalance(tenantId, current, newBalance, "AVAILABLE");
                repository.insertLedger(tenantId, new EntitlementLedgerEntry(null, current.id(), "CORRECTION_IN",
                        command.count(), "REDEMPTION_REVERSAL", command.sourceRef(), command.idempotencyKey(),
                        actorUserId, "核销冲正审批恢复", null, null));
                assertLedgerBalance(tenantId, current.id(), newBalance);
                updated.add(repository.findEntitlement(tenantId, current.id()).orElseThrow());
            }
            orderSourceRefs.putIfAbsent(view.orderId(), command.sourceRef());
        }
        for (Map.Entry<Long, String> entry : orderSourceRefs.entrySet()) {
            Order lockedOrder = locked.ordersById().get(entry.getKey());
            if (lockedOrder == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
            }
            recalculateOrderStatus(tenantId, actorUserId, lockedOrder, entry.getValue(), "REDEMPTION_REVERSED");
        }
        return List.copyOf(updated);
    }

    @Override
    public void recalculateOrderStatusAfterRefund(long tenantId, Long actorUserId, Order order, long refundId) {
        recalculateOrderStatus(tenantId, actorUserId, order, Long.toString(refundId), "REFUND_SUCCEEDED");
    }

    @Override
    @Transactional(readOnly = true)
    public List<EntitlementCorrectionView> corrections(long tenantId, long reversalId) {
        return repository.findLedgerBySource(tenantId, "REDEMPTION_REVERSAL", Long.toString(reversalId)).stream()
                .filter(entry -> "CORRECTION_IN".equals(entry.txnType()))
                .map(entry -> {
                    Entitlement entitlement = repository.findEntitlement(tenantId, entry.entitlementId()).orElseThrow();
                    return new EntitlementCorrectionView(entry.id(), entry.entitlementId(), entitlement.projectId(),
                            entry.txnType(), entry.deltaCount(), entry.sourceRef(), entry.idempotencyKey(),
                            entry.actorUserId(), entry.reason(), entry.createdAt());
                })
                .toList();
    }

    private void validateOriginalRedemption(long tenantId, long entitlementId,
                                            String originalSourceRef, int originalCount) {
        List<EntitlementLedgerEntry> original = repository.findLedgerBySource(tenantId, "REDEMPTION", originalSourceRef)
                .stream()
                .filter(entry -> entry.entitlementId().equals(entitlementId) && "REDEEM".equals(entry.txnType()))
                .toList();
        if (original.size() != 1 || !Integer.valueOf(-originalCount).equals(original.get(0).deltaCount())
                || !("redemption.post:" + originalSourceRef).equals(original.get(0).idempotencyKey())) {
            throw new BusinessException(ErrorCode.CONFLICT, "原核销负流水与核销明细或来源不一致，禁止冲正");
        }
    }

    private void validateUse(FulfillmentEntitlementView view, long customerId, long institutionId,
                             long projectId, int count, Instant serviceAt) {
        validatePaidSnapshot(view, customerId, projectId);
        Entitlement entitlement = view.entitlement();
        if (!view.institutionIds().contains(institutionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单快照不允许在该机构使用该权益");
        }
        if (count <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "预约或核销次数必须为正整数");
        }
        if (!"AVAILABLE".equals(entitlement.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益当前状态不可履约");
        }
        LocalDate serviceDate = serviceAt.atZone(BUSINESS_ZONE).toLocalDate();
        if (entitlement.validFrom() != null && entitlement.validFrom().isAfter(serviceDate)) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益在计划或服务日期尚未生效");
        }
        if (entitlement.validTo() != null && entitlement.validTo().isBefore(serviceDate)) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益在计划或服务日期已过期");
        }
        if (count > entitlement.availableCount()) {
            throw new BusinessException(ErrorCode.CONFLICT, "计划或核销次数超过可用余额");
        }
    }

    private void validatePaidSnapshot(FulfillmentEntitlementView view, long customerId, long projectId) {
        Entitlement entitlement = view.entitlement();
        if (!entitlement.customerId().equals(customerId) || !entitlement.projectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益批次与客户或预约子项目不一致");
        }
        if (!view.institutionIds().isEmpty() && view.orderId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益缺少已支付订单快照");
        }
    }

    private LockedEntitlementBatch lockEntitlementBatch(long tenantId, List<Long> entitlementIds) {
        List<Long> sortedEntitlementIds = entitlementIds.stream().distinct().sorted().toList();
        Map<Long, OrderItem> orderItemsByEntitlement = new LinkedHashMap<>();
        List<Long> orderIds = new ArrayList<>();
        for (Long entitlementId : sortedEntitlementIds) {
            Entitlement preRead = repository.findEntitlement(tenantId, entitlementId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在"));
            OrderItem item = repository.findOrderItem(tenantId, preRead.orderItemId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单行快照"));
            orderItemsByEntitlement.put(entitlementId, item);
            orderIds.add(item.orderId());
        }
        List<Long> sortedOrderIds = orderIds.stream().distinct().sorted().toList();
        List<Order> lockedOrders = repository.findOrdersForUpdate(tenantId, sortedOrderIds);
        if (lockedOrders.size() != sortedOrderIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益缺少订单");
        }
        Map<Long, Order> ordersById = lockedOrders.stream()
                .collect(Collectors.toMap(Order::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<Entitlement> lockedEntitlements = repository.findEntitlementsByOrderIdsForUpdate(tenantId, sortedOrderIds);
        Map<Long, Entitlement> lockedById = lockedEntitlements.stream()
                .collect(Collectors.toMap(Entitlement::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Long, FulfillmentEntitlementView> viewsByEntitlementId = new LinkedHashMap<>();
        for (Long entitlementId : sortedEntitlementIds) {
            Entitlement lockedEntitlement = lockedById.get(entitlementId);
            if (lockedEntitlement == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在");
            }
            OrderItem item = orderItemsByEntitlement.get(lockedEntitlement.id());
            if (item == null || !lockedEntitlement.orderItemId().equals(item.id())) {
                item = repository.findOrderItem(tenantId, lockedEntitlement.orderItemId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单行快照"));
            }
            Order lockedOrder = ordersById.get(item.orderId());
            if (lockedOrder == null) {
                throw new BusinessException(ErrorCode.CONFLICT, "权益缺少订单");
            }
            viewsByEntitlementId.put(lockedEntitlement.id(), buildSnapshot(item, lockedOrder, lockedEntitlement));
        }
        return new LockedEntitlementBatch(ordersById, viewsByEntitlementId);
    }

    private FulfillmentEntitlementView requireLockedView(LockedEntitlementBatch batch, long entitlementId) {
        FulfillmentEntitlementView view = batch.viewsByEntitlementId().get(entitlementId);
        if (view == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在");
        }
        return view;
    }

    private FulfillmentEntitlementView snapshot(long tenantId, Entitlement entitlement) {
        OrderItem item = repository.findOrderItem(tenantId, entitlement.orderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单行快照"));
        Order order = repository.findOrder(tenantId, item.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单"));
        return buildSnapshot(item, order, entitlement);
    }

    private FulfillmentEntitlementView buildSnapshot(OrderItem item, Order order, Entitlement entitlement) {
        if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())
                || !order.customerId().equals(entitlement.customerId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已支付订单的权益可进入履约");
        }
        try {
            JsonNode snapshot = objectMapper.readTree(item.entitlementSnapshot());
            boolean projectFound = false;
            for (JsonNode project : snapshot.path("projectSnapshots")) {
                if (project.path("projectId").asLong() == entitlement.projectId()
                        && project.path("grantCount").asInt() > 0) {
                    projectFound = true;
                    break;
                }
            }
            if (!projectFound) {
                throw new BusinessException(ErrorCode.CONFLICT, "订单快照不包含该子项目权益");
            }
            List<Long> institutionIds = new ArrayList<>();
            snapshot.path("institutionIds").forEach(id -> institutionIds.add(id.asLong()));
            return new FulfillmentEntitlementView(entitlement, order.id(), List.copyOf(institutionIds),
                    snapshot.path("allocationStatus").asText("PENDING_Q02"));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("订单权益快照解析失败", ex);
        }
    }

    private void recalculateOrderStatus(long tenantId, Long actorUserId, Order order,
                                        String sourceRef, String triggerType) {
        long orderId = order.id();
        if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单当前状态不允许履约重算");
        }
        List<Entitlement> entitlements = repository.findEntitlementsByOrderIdsForUpdate(tenantId, List.of(orderId));
        if (entitlements.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已支付订单缺少权益，无法重算履约状态");
        }
        long totalGranted = 0;
        long netRedeemed = 0;
        long netRefundRevoked = 0;
        for (Entitlement entitlement : entitlements) {
            if (repository.sumLedgerForUpdate(tenantId, entitlement.id()) != entitlement.availableCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "订单权益账本与快照不一致，履约事务已回滚");
            }
            totalGranted = Math.addExact(totalGranted, entitlement.grantedCount());
            for (EntitlementLedgerEntry entry : repository.findLedgerByEntitlementForUpdate(tenantId, entitlement.id())) {
                if ("REDEEM".equals(entry.txnType()) || "CORRECTION_IN".equals(entry.txnType())) {
                    netRedeemed = Math.subtractExact(netRedeemed, entry.deltaCount().longValue());
                } else if ("REFUND_REVOKE".equals(entry.txnType())) {
                    netRefundRevoked = Math.subtractExact(netRefundRevoked, entry.deltaCount().longValue());
                }
            }
        }
        long effectiveGranted = Math.subtractExact(totalGranted, netRefundRevoked);
        if (totalGranted <= 0 || netRefundRevoked < 0 || effectiveGranted < 0
                || netRedeemed < 0 || netRedeemed > effectiveGranted) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单履约流水不一致，状态重算已回滚");
        }
        String targetStatus = netRedeemed == 0 ? "PAID"
                : netRedeemed == effectiveGranted ? "FULFILLED" : "PARTIALLY_REDEEMED";
        if (targetStatus.equals(order.status())) {
            return;
        }
        if (repository.updateOrderStatus(tenantId, orderId, order.version(), order.status(), targetStatus,
                null, null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单履约状态版本冲突，请重试");
        }
        repository.insertStatusHistory(tenantId, new OrderStatusHistory(null, orderId, order.status(), targetStatus,
                actorUserId, triggerType, sourceRef, null, null));
    }

    private LocalDate today() {
        return clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
    }

    private void updateBalance(long tenantId, Entitlement current, int newBalance, String newStatus) {
        if (repository.updateEntitlementBalance(tenantId, current.id(), current.version(), newBalance, newStatus,
                current.versionNo() + 1) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益已被其他履约流程修改，请重试");
        }
    }

    private void validateLedger(EntitlementLedgerEntry existing, long entitlementId, String txnType,
                                int deltaCount, String sourceRef) {
        if (!existing.entitlementId().equals(entitlementId) || !txnType.equals(existing.txnType())
                || !Integer.valueOf(deltaCount).equals(existing.deltaCount())
                || !Objects.equals(existing.sourceRef(), sourceRef)) {
            throw new BusinessException(ErrorCode.CONFLICT, "履约幂等键已用于不同账本请求");
        }
    }

    private void assertLedgerBalance(long tenantId, long entitlementId, int expected) {
        if (repository.sumLedgerForUpdate(tenantId, entitlementId) != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益账本与余额快照不一致，事务已回滚");
        }
    }
}
