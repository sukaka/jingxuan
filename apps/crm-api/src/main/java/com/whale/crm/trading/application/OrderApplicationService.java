package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.customer.api.dto.CustomerTimelineItemResponse;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTimelineCursor;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.CatalogRules;
import com.whale.crm.product.domain.PackageOptionSelection;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageSelectionGroup;
import com.whale.crm.product.domain.PackageSelectionRules;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderItemRequest;
import com.whale.crm.trading.api.dto.OrderReviewRequest;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.OrderItemSelection;
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderApplicationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final TradingRepository repository;
    private final CatalogApplicationService catalogService;
    private final CampaignApplicationService campaignService;
    private final DictionaryApplicationService dictionaryService;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public OrderApplicationService(TradingRepository repository, CatalogApplicationService catalogService,
                                   CampaignApplicationService campaignService,
                                   DictionaryApplicationService dictionaryService,
                                   CustomerScopeSupport scopeSupport, AuditService auditService,
                                   ObjectMapper objectMapper) {
        this(repository, catalogService, campaignService, dictionaryService, scopeSupport, auditService,
                objectMapper, Clock.system(BUSINESS_ZONE));
    }

    OrderApplicationService(TradingRepository repository, CatalogApplicationService catalogService,
                            CampaignApplicationService campaignService,
                            DictionaryApplicationService dictionaryService,
                            CustomerScopeSupport scopeSupport, AuditService auditService,
                            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.campaignService = campaignService;
        this.dictionaryService = dictionaryService;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Order> search(long tenantId, CurrentUser user, Long customerId, String status,
                                      int page, int size) {
        DataScope scope = scopeSupport.scope(user);
        if (scopeSupport.isEmptyScope(scope)) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        CustomerScopeSupport.ScopeQuery query = scopeSupport.queryOf(scope, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchOrders(tenantId, query.scopeType(), query.teamIds(), query.selfId(),
                        customerId, status, offset, size),
                repository.countOrders(tenantId, query.scopeType(), query.teamIds(), query.selfId(), customerId, status),
                page, size);
    }

    @Transactional(readOnly = true)
    public List<CustomerTimelineItemResponse> timeline(long tenantId, CurrentUser user, long customerId,
                                                        CustomerTimelineCursor cursor) {
        scopeSupport.requireVisible(tenantId, customerId, user);
        List<CustomerTimelineItemResponse> events = new ArrayList<>();
        int page = 1;
        long total;
        do {
            PageResponse<Order> result = search(tenantId, user, customerId, null, page, 100);
            total = result.total();
            for (Order order : result.items()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", order.status());
                details.put("payableFen", order.payableFen());
                details.put("paidFen", order.paidFen());
                details.put("refundedFen", order.refundedFen());
                Instant occurredAt = order.createdAt() != null ? order.createdAt() : order.orderedAt();
                CustomerTimelineItemResponse event = CustomerTimelineItemResponse.of("ORDER", order.id(), "ORDER",
                        occurredAt, "订单 " + order.orderNo(), "订单状态：" + orderStatusLabel(order.status()), details,
                        order.soldByUserId());
                if (cursor == null || cursor.allows(event)) {
                    events.add(event);
                }
            }
            page++;
        } while ((long) (page - 1) * 100 < total);
        return events;
    }

    private static String orderStatusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "PENDING_PAYMENT" -> "待支付";
            case "PAID" -> "已支付";
            case "PARTIALLY_REDEEMED" -> "部分核销";
            case "FULFILLED" -> "已完成";
            case "PARTIALLY_REFUNDED", "PARTIAL_REFUNDED" -> "部分退款";
            case "REFUNDED" -> "已退款";
            case "CANCELED", "CANCELLED" -> "已取消";
            case "CLOSED" -> "已关闭";
            default -> "其他状态";
        };
    }

    @Transactional
    public OrderFulfillmentView create(long tenantId, CurrentUser user, CreateOrderRequest request) {
        return createInternal(tenantId, user, request, "CRM", null, null);
    }

    @Transactional
    public OrderFulfillmentView createExternalOrder(long tenantId, CurrentUser user, CreateOrderRequest request,
                                                    String sourceSystem, String externalShopId, String externalOrderNo) {
        return createInternal(tenantId, user, request, sourceSystem, externalShopId, externalOrderNo);
    }

    private OrderFulfillmentView createInternal(long tenantId, CurrentUser user, CreateOrderRequest request,
                                                String sourceSystem, String externalShopId, String externalOrderNo) {
        Customer customer = scopeSupport.requireVisibleForUpdate(tenantId, request.customerId(), user);
        Instant now = clock.instant();
        LocalDate validFrom = now.atZone(BUSINESS_ZONE).toLocalDate();
        OrderAttributionSnapshot attribution = campaignService.resolveOrderAttribution(tenantId,
                request.campaignId(), request.campaignBatchId(), request.channelId(), now);
        DictionaryOptionResponse orderType = dictionaryService.requireActiveOption(
                tenantId, "orderTypes", request.orderTypeCode());
        List<PreparedItem> prepared = request.items().stream()
                .map(item -> prepareItem(tenantId, user, item, validFrom, request.validTo())).toList();
        long listAmount = prepared.stream().mapToLong(PreparedItem::listAmountFen).sum();
        if (request.payableFen() > listAmount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "应付金额不能高于订单原价");
        }
        allocatePayable(request.payableFen(), prepared);

        String temporaryNo = "TMP-" + UUID.randomUUID();
        Order input = new Order(null, temporaryNo, customer.getId(), null, user.userId(), customer.getOwnerStaffId(),
                externalOrderNo, sourceSystem, externalShopId, orderType.optionCode(), orderType.optionLabel(),
                attribution.campaignId(), attribution.campaignBatchId(),
                attribution.campaignCodeSnapshot(), attribution.campaignNameSnapshot(),
                attribution.batchCodeSnapshot(), attribution.batchNameSnapshot(),
                attribution.channelId(), attribution.businessOwnerAtSaleId(), "DRAFT",
                request.receiverType(), listAmount, listAmount - request.payableFen(), request.payableFen(), 0L, 0L,
                now, null, null, null);
        repository.insertOrder(tenantId, input);
        Order created = repository.findOrderByNo(tenantId, temporaryNo).orElseThrow();
        String orderNo = String.format("J%012d", created.id());
        repository.backfillOrderNo(tenantId, created.id(), orderNo);
        for (PreparedItem item : prepared) {
            repository.insertOrderItem(tenantId, item.toOrderItem(created.id()));
        }
        List<OrderItem> persistedItems = repository.findOrderItems(tenantId, created.id());
        if (persistedItems.size() != prepared.size()) {
            throw new IllegalStateException("订单行快照保存不完整");
        }
        for (int i = 0; i < prepared.size(); i++) {
            long orderItemId = persistedItems.get(i).id();
            for (OrderItemSelection selection : prepared.get(i).selections()) {
                repository.insertOrderItemSelection(tenantId, selection.withOrderItemId(orderItemId));
            }
        }
        repository.insertStatusHistory(tenantId, new OrderStatusHistory(null, created.id(), "NONE", "DRAFT",
                user.userId(), "CREATE", null, null, null));
        OrderFulfillmentView after = detail(tenantId, user, created.id());
        auditService.record(tenantId, user.userId(), "ORDER_CREATE", "ORDER", Long.toString(created.id()), null, after, null);
        return after;
    }

    @Transactional
    public OrderFulfillmentView submit(long tenantId, CurrentUser user, long orderId, OrderReviewRequest request) {
        Order before = requireVisibleOrder(tenantId, user, orderId);
        if (!"DRAFT".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅草稿状态的订单可提交待支付");
        }
        changeStatus(tenantId, user, before, "PENDING_PAYMENT", request.version(), "SUBMIT", null, request.reason());
        OrderFulfillmentView after = detail(tenantId, user, orderId);
        auditService.record(tenantId, user.userId(), "ORDER_SUBMIT", "ORDER", Long.toString(orderId), before, after, request.reason());
        return after;
    }

    @Transactional
    public OrderFulfillmentView cancel(long tenantId, CurrentUser user, long orderId, OrderReviewRequest request) {
        Order before = requireVisibleOrder(tenantId, user, orderId);
        if (!"DRAFT".equals(before.status()) && !"PENDING_PAYMENT".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "已支付订单不能取消，请走退款流程");
        }
        changeStatus(tenantId, user, before, "CANCELED", request.version(), "CANCEL", null, request.reason());
        OrderFulfillmentView after = detail(tenantId, user, orderId);
        auditService.record(tenantId, user.userId(), "ORDER_CANCEL", "ORDER", Long.toString(orderId), before, after, request.reason());
        return after;
    }

    @Transactional(readOnly = true)
    public OrderFulfillmentView detail(long tenantId, CurrentUser user, long orderId) {
        Order order = requireVisibleOrder(tenantId, user, orderId);
        List<OrderItem> items = repository.findOrderItems(tenantId, orderId);
        List<Long> itemIds = items.stream().map(OrderItem::id).toList();
        List<Entitlement> entitlements = repository.findEntitlementsByOrderItemIds(tenantId, itemIds);
        return new OrderFulfillmentView(new OrderDetail(order, items, repository.findPaymentsByOrder(tenantId, orderId),
                repository.findStatusHistory(tenantId, orderId)), entitlements);
    }

    private PreparedItem prepareItem(long tenantId, CurrentUser user, OrderItemRequest request,
                                     LocalDate validFrom, LocalDate validTo) {
        PackageVersionSnapshot snapshot = catalogService.snapshotBySku(tenantId, user, request.skuId());
        Sku sku = snapshot.skus().stream().filter(item -> item.id().equals(request.skuId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在"));
        if (!"ACTIVE".equals(sku.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 未上架，不能创建订单");
        }
        if (!snapshot.entitlementIssuable()) {
            if ("PENDING_Q01".equals(snapshot.rulesStatus())) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "套餐选择规则尚未确认，当前仅可保存模板，暂不支持发放权益",
                        List.of(new ApiResponse.ErrorDetail("Q01", "请配置选项组、选择数和每个项目的次数后再下单")));
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "套餐权益配置不完整，不能创建订单");
        }
        if (snapshot.institutions().isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 未配置有效合作机构，不能创建订单");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "权益有效期截止日期不能早于生效日期");
        }
        List<PackageOptionSelection> requestedSelections = request.selections() == null ? List.of()
                : request.selections().stream()
                        .map(selection -> new PackageOptionSelection(selection.groupCode(), selection.projectId())).toList();
        List<PackageProject> selectedProjects = PackageSelectionRules.select(snapshot.selectionMode(), snapshot.groups(),
                snapshot.projects(), requestedSelections);
        String allocationStatus = CatalogRules.allocationStatus(selectedProjects);
        int quantity = request.quantity();
        long listAmount = Math.multiplyExact(sku.salePriceFen(), quantity);
        long referenceAmount = Math.multiplyExact(PackageSelectionRules.referenceAmountFen(selectedProjects), quantity);
        List<Map<String, Object>> projectSnapshots = selectedProjects.stream().map(project -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", project.projectId());
            item.put("projectCode", project.projectCode());
            item.put("projectName", project.projectName());
            item.put("grantCount", Math.multiplyExact(project.grantCount(), quantity));
            item.put("allocationPriceFen", project.allocationPriceFen());
            item.put("optionGroup", project.optionGroup());
            item.put("referencePriceFen", project.referencePriceFen());
            item.put("referenceAmountFen", Math.multiplyExact(
                    Math.multiplyExact(project.referencePriceFen(), project.grantCount().longValue()), quantity));
            return item;
        }).toList();
        List<OrderItemSelection> selections = snapshot.groups().isEmpty() ? List.of() : selectedProjects.stream()
                .map(project -> selectionSnapshot(snapshot, project, quantity)).toList();
        Map<String, Object> entitlementSnapshot = new LinkedHashMap<>();
        entitlementSnapshot.put("packageVersionId", snapshot.id());
        entitlementSnapshot.put("packageCode", snapshot.packageCode());
        entitlementSnapshot.put("packageName", snapshot.packageName());
        entitlementSnapshot.put("versionNo", snapshot.versionNo());
        entitlementSnapshot.put("selectionMode", snapshot.selectionMode());
        entitlementSnapshot.put("choiceCount", snapshot.choiceCount());
        entitlementSnapshot.put("selectionRules", snapshot.selectionRules());
        entitlementSnapshot.put("projectSnapshots", projectSnapshots);
        entitlementSnapshot.put("selectionSnapshots", selections.stream().map(this::selectionSnapshotMap).toList());
        entitlementSnapshot.put("packageSalePriceFen", sku.salePriceFen());
        entitlementSnapshot.put("referenceAmountFen", referenceAmount);
        entitlementSnapshot.put("referenceDiscountAmountFen", Math.max(0L, referenceAmount - listAmount));
        entitlementSnapshot.put("institutionIds", snapshot.institutions().stream().map(item -> item.id()).toList());
        entitlementSnapshot.put("validFrom", validFrom.toString());
        entitlementSnapshot.put("validTo", validTo == null ? null : validTo.toString());
        entitlementSnapshot.put("allocationStatus", allocationStatus);
        long weight = selectedProjects.stream().mapToLong(item -> item.allocationPriceFen() == null ? 0L
                : Math.multiplyExact(item.allocationPriceFen(), quantity)).sum();
        return new PreparedItem(null, sku.id(), snapshot.id(), sku.skuCode(),
                snapshot.packageName() + "｜" + sku.specName(), quantity, listAmount, 0L,
                entitlementSnapshot, weight, allocationStatus, selections);
    }

    private OrderItemSelection selectionSnapshot(PackageVersionSnapshot snapshot, PackageProject project, int quantity) {
        PackageSelectionGroup group = snapshot.groups().stream()
                .filter(item -> item.groupCode().equals(project.optionGroup())).findFirst()
                .orElseThrow(() -> new IllegalStateException("选项组快照不完整"));
        return new OrderItemSelection(null, snapshot.id(), group.groupCode(), group.groupName(),
                group.minSelections(), group.maxSelections(), project.projectId(), project.projectCode(),
                project.projectName(), Math.multiplyExact(project.grantCount(), quantity),
                project.referencePriceFen(), group.sortOrder());
    }

    private Map<String, Object> selectionSnapshotMap(OrderItemSelection selection) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("groupCode", selection.groupCode());
        item.put("groupName", selection.groupName());
        item.put("minSelections", selection.minSelections());
        item.put("maxSelections", selection.maxSelections());
        item.put("projectId", selection.projectId());
        item.put("projectCode", selection.projectCodeSnapshot());
        item.put("projectName", selection.projectNameSnapshot());
        item.put("grantCount", selection.grantCount());
        item.put("referencePriceFen", selection.referencePriceFen());
        item.put("sortOrder", selection.sortOrder());
        return item;
    }

    private void allocatePayable(long payable, List<PreparedItem> items) {
        boolean missingLineWeight = items.size() > 1 && items.stream().anyMatch(item -> item.weight() <= 0);
        if (missingLineWeight || items.stream().anyMatch(item -> "PENDING_Q02".equals(item.allocationStatus()))) {
            for (PreparedItem item : items) {
                item.entitlementSnapshot().put("allocationStatus", "PENDING_Q02");
                item.entitlementSnapshot().put("paidAllocatedFen", 0L);
                projectSnapshots(item).forEach(project -> project.put("paidAllocatedFen", 0L));
            }
            return;
        }
        long[] allocated = items.size() == 1 ? new long[]{payable}
                : largestRemainder(payable, items.stream().mapToLong(PreparedItem::weight).toArray());
        for (int i = 0; i < items.size(); i++) {
            PreparedItem item = items.get(i);
            item.entitlementSnapshot().put("allocationStatus", item.allocationStatus());
            item.entitlementSnapshot().put("paidAllocatedFen", allocated[i]);
            allocateLineToProjects(item, allocated[i]);
        }
    }

    private void allocateLineToProjects(PreparedItem item, long allocated) {
        List<Map<String, Object>> projects = projectSnapshots(item);
        if (projects.size() == 1) {
            projects.get(0).put("paidAllocatedFen", allocated);
            return;
        }
        long[] weights = projects.stream().mapToLong(project -> Math.multiplyExact(
                ((Number) project.get("allocationPriceFen")).longValue(), item.purchaseQuantity())).toArray();
        long[] projectAllocations = largestRemainder(allocated, weights);
        for (int i = 0; i < projects.size(); i++) {
            projects.get(i).put("paidAllocatedFen", projectAllocations[i]);
        }
    }

    private List<Map<String, Object>> projectSnapshots(PreparedItem item) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects =
                (List<Map<String, Object>>) item.entitlementSnapshot().get("projectSnapshots");
        return projects;
    }

    private long[] largestRemainder(long total, long[] weights) {
        long totalWeight = 0;
        for (long weight : weights) {
            if (weight <= 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "项目分摊权重必须大于 0");
            }
            totalWeight += weight;
        }
        long[] result = new long[weights.length];
        BigDecimal[] remainders = new BigDecimal[weights.length];
        long floorSum = 0;
        for (int i = 0; i < weights.length; i++) {
            BigDecimal raw = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(weights[i]))
                    .divide(BigDecimal.valueOf(totalWeight), 20, RoundingMode.HALF_UP);
            result[i] = raw.longValue();
            floorSum += result[i];
            remainders[i] = raw.subtract(BigDecimal.valueOf(result[i]));
        }
        long remaining = total - floorSum;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed().thenComparingInt(i -> i));
        for (int i = 0; i < remaining; i++) {
            result[order.get(i % order.size())]++;
        }
        return result;
    }

    private Order requireVisibleOrder(long tenantId, CurrentUser user, long orderId) {
        Order order = repository.findOrder(tenantId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在数据范围内"));
        scopeSupport.requireVisible(tenantId, order.customerId(), user);
        return order;
    }

    private void changeStatus(long tenantId, CurrentUser user, Order before, String toStatus, long version,
                              String trigger, String triggerRef, String reason) {
        if (repository.updateOrderStatus(tenantId, before.id(), version, before.status(), toStatus, null, null) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "订单状态或版本已变化，请刷新后重试");
        }
        repository.insertStatusHistory(tenantId, new OrderStatusHistory(null, before.id(), before.status(), toStatus,
                user.userId(), trigger, triggerRef, reason, null));
    }

    private record PreparedItem(Long id, Long skuId, Long packageVersionId, String skuCodeSnapshot,
                                String skuNameSnapshot, Integer purchaseQuantity, Long listAmountFen,
                                Long paidAllocatedFen, Map<String, Object> entitlementSnapshot,
                                long weight, String allocationStatus, List<OrderItemSelection> selections) {
        OrderItem toOrderItem(long orderId) {
            try {
                long allocated = ((Number) entitlementSnapshot.get("paidAllocatedFen")).longValue();
                return new OrderItem(id, orderId, skuId, packageVersionId, skuCodeSnapshot, skuNameSnapshot,
                        purchaseQuantity, listAmountFen, allocated,
                        new ObjectMapper().writeValueAsString(entitlementSnapshot), null);
            } catch (Exception ex) {
                throw new IllegalStateException("订单行快照序列化失败", ex);
            }
        }
    }
}
