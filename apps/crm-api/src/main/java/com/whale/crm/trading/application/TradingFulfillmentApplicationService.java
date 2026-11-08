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
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class TradingFulfillmentApplicationService implements FulfillmentTradingOperations {
    private final TradingRepository repository;
    private final CustomerScopeSupport scopeSupport;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public TradingFulfillmentApplicationService(TradingRepository repository, CustomerScopeSupport scopeSupport,
                                                OutboxService outboxService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeSupport = scopeSupport;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentEntitlementView> appointmentOptions(long tenantId, CurrentUser user, long customerId) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        DataScope scope = scopeSupport.scope(user);
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
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
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
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
    public FulfillmentEntitlementView requireAppointmentEntitlement(long tenantId, long entitlementId, long customerId,
                                                                    long institutionId, int count, Instant scheduledAt) {
        Entitlement entitlement = repository.findEntitlementForUpdate(tenantId, entitlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在"));
        FulfillmentEntitlementView view = snapshot(tenantId, entitlement);
        validateUse(view, customerId, institutionId, entitlement.projectId(), count, scheduledAt);
        return view;
    }

    @Override
    public FulfillmentEntitlementView lockRedeemable(long tenantId, long entitlementId, long customerId,
                                                     long institutionId, long projectId, int count, Instant serviceAt) {
        Entitlement entitlement = repository.findEntitlementForUpdate(tenantId, entitlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在"));
        FulfillmentEntitlementView view = snapshot(tenantId, entitlement);
        validateUse(view, customerId, institutionId, projectId, count, serviceAt);
        return view;
    }

    @Override
    public FulfillmentEntitlementView lockReversible(long tenantId, long entitlementId, long customerId, long projectId,
                                                     String originalSourceRef, int originalCount) {
        Entitlement entitlement = repository.findEntitlementForUpdate(tenantId, entitlementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "权益批次不存在"));
        FulfillmentEntitlementView view = snapshot(tenantId, entitlement);
        validatePaidSnapshot(view, customerId, projectId);
        if (!List.of("AVAILABLE", "EXHAUSTED").contains(entitlement.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益当前状态不允许直接冲正恢复");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (entitlement.validTo() != null && entitlement.validTo().isBefore(today)) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益已过期，不能通过冲正悄悄恢复可用次数");
        }
        validateOriginalRedemption(tenantId, entitlementId, originalSourceRef, originalCount);
        return view;
    }

    @Override
    public Entitlement appendRedemption(long tenantId, long actorUserId, FulfillmentEntitlementView locked,
                                        int count, String sourceRef, String idempotencyKey) {
        Entitlement current = locked.entitlement();
        EntitlementLedgerEntry existing = repository.findLedgerByKey(tenantId, idempotencyKey).orElse(null);
        if (existing != null) {
            validateLedger(existing, current.id(), "REDEEM", -count, sourceRef);
            return repository.findEntitlement(tenantId, current.id()).orElseThrow();
        }
        int newBalance = current.availableCount() - count;
        if (count <= 0 || newBalance < 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益余额不足，不能出现负数余额");
        }
        String newStatus = newBalance == 0 ? "EXHAUSTED" : "AVAILABLE";
        updateBalance(tenantId, current, newBalance, newStatus);
        repository.insertLedger(new EntitlementLedgerEntry(null, current.id(), "REDEEM", -count, "REDEMPTION",
                sourceRef, idempotencyKey, actorUserId, "机构预约正式核销", null, null));
        assertLedgerBalance(tenantId, current.id(), newBalance);
        recalculateOrderStatus(tenantId, actorUserId, locked.orderId(), sourceRef, "REDEMPTION_POSTED");
        outboxService.append(tenantId, "entitlement.redeemed.v1:" + idempotencyKey, "entitlement.redeemed.v1",
                "entitlement", Long.toString(current.id()), Map.of("entitlementId", current.id(),
                        "count", count, "sourceRef", sourceRef));
        return repository.findEntitlement(tenantId, current.id()).orElseThrow();
    }

    @Override
    public Entitlement appendReversal(long tenantId, long actorUserId, FulfillmentEntitlementView locked,
                                      int count, String sourceRef, String idempotencyKey) {
        Entitlement current = locked.entitlement();
        EntitlementLedgerEntry existing = repository.findLedgerByKey(tenantId, idempotencyKey).orElse(null);
        if (existing != null) {
            validateLedger(existing, current.id(), "CORRECTION_IN", count, sourceRef);
            return repository.findEntitlement(tenantId, current.id()).orElseThrow();
        }
        if (count <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "冲正次数必须为正整数");
        }
        int newBalance = Math.addExact(current.availableCount(), count);
        if (newBalance > current.grantedCount()) {
            throw new BusinessException(ErrorCode.CONFLICT, "冲正恢复后的余额不能超过权益发放次数");
        }
        updateBalance(tenantId, current, newBalance, "AVAILABLE");
        repository.insertLedger(new EntitlementLedgerEntry(null, current.id(), "CORRECTION_IN", count,
                "REDEMPTION_REVERSAL", sourceRef, idempotencyKey, actorUserId, "核销冲正审批恢复", null, null));
        assertLedgerBalance(tenantId, current.id(), newBalance);
        recalculateOrderStatus(tenantId, actorUserId, locked.orderId(), sourceRef, "REDEMPTION_REVERSED");
        return repository.findEntitlement(tenantId, current.id()).orElseThrow();
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
        LocalDate serviceDate = serviceAt.atZone(ZoneOffset.UTC).toLocalDate();
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

    private FulfillmentEntitlementView snapshot(long tenantId, Entitlement entitlement) {
        OrderItem item = repository.findOrderItem(tenantId, entitlement.orderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单行快照"));
        Order order = repository.findOrder(tenantId, item.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "权益缺少订单"));
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

    private void recalculateOrderStatus(long tenantId, long actorUserId, long orderId,
                                        String sourceRef, String triggerType) {
        Order order = repository.findOrderForUpdate(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (!List.of("PAID", "PARTIALLY_REDEEMED", "FULFILLED").contains(order.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单当前状态不允许履约重算");
        }
        List<OrderItem> items = repository.findOrderItems(tenantId, orderId);
        List<Entitlement> entitlements = repository.findEntitlementsByOrderItemIds(tenantId,
                items.stream().map(OrderItem::id).toList());
        if (entitlements.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已支付订单缺少权益，无法重算履约状态");
        }
        long totalGranted = 0;
        long netRedeemed = 0;
        for (Entitlement entitlement : entitlements) {
            if (repository.sumLedger(tenantId, entitlement.id()) != entitlement.availableCount()) {
                throw new BusinessException(ErrorCode.CONFLICT, "订单权益账本与快照不一致，履约事务已回滚");
            }
            totalGranted = Math.addExact(totalGranted, entitlement.grantedCount());
            for (EntitlementLedgerEntry entry : repository.findLedgerByEntitlement(tenantId, entitlement.id())) {
                if ("REDEEM".equals(entry.txnType()) || "CORRECTION_IN".equals(entry.txnType())) {
                    netRedeemed = Math.subtractExact(netRedeemed, entry.deltaCount().longValue());
                }
            }
        }
        if (totalGranted <= 0 || netRedeemed < 0 || netRedeemed > totalGranted) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单履约流水不一致，状态重算已回滚");
        }
        String targetStatus = netRedeemed == 0 ? "PAID"
                : netRedeemed == totalGranted ? "FULFILLED" : "PARTIALLY_REDEEMED";
        if (targetStatus.equals(order.status())) {
            return;
        }
        if (repository.updateOrderStatus(tenantId, orderId, order.version(), order.status(), targetStatus,
                null, null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单履约状态版本冲突，请重试");
        }
        repository.insertStatusHistory(new OrderStatusHistory(null, orderId, order.status(), targetStatus,
                actorUserId, triggerType, sourceRef, null, null));
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
        if (repository.sumLedger(tenantId, entitlementId) != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "权益账本与余额快照不一致，事务已回滚");
        }
    }
}
