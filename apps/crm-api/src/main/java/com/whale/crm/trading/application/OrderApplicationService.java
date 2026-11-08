package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.customer.application.CustomerScopeSupport;
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
import com.whale.crm.trading.domain.OrderStatusHistory;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderApplicationService {
    private final TradingRepository repository;
    private final CatalogApplicationService catalogService;
    private final CampaignApplicationService campaignService;
    private final DictionaryApplicationService dictionaryService;
    private final CustomerScopeSupport scopeSupport;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public OrderApplicationService(TradingRepository repository, CatalogApplicationService catalogService,
                                   CampaignApplicationService campaignService,
                                   DictionaryApplicationService dictionaryService,
                                   CustomerScopeSupport scopeSupport, AuditService auditService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.campaignService = campaignService;
        this.dictionaryService = dictionaryService;
        this.scopeSupport = scopeSupport;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
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
        Customer customer = scopeSupport.requireVisible(tenantId, request.customerId(), user);
        Instant now = Instant.now();
        OrderAttributionSnapshot attribution = campaignService.resolveOrderAttribution(tenantId,
                request.campaignId(), request.campaignBatchId(), request.channelId(), now);
        DictionaryOptionResponse orderType = dictionaryService.requireActiveOption(
                tenantId, "orderTypes", request.orderTypeCode());
        List<PreparedItem> prepared = request.items().stream()
                .map(item -> prepareItem(tenantId, user, item, request.validTo())).toList();
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
        repository.insertOrder(input);
        Order created = repository.findOrderByNo(tenantId, temporaryNo).orElseThrow();
        String orderNo = String.format("J%012d", created.id());
        repository.backfillOrderNo(tenantId, created.id(), orderNo);
        for (PreparedItem item : prepared) {
            repository.insertOrderItem(item.toOrderItem(created.id()));
        }
        repository.insertStatusHistory(new OrderStatusHistory(null, created.id(), "NONE", "DRAFT", user.userId(),
                "CREATE", null, null, null));
        OrderFulfillmentView after = detail(tenantId, user, created.id());
        auditService.record(tenantId, user.userId(), "ORDER_CREATE", "ORDER", Long.toString(created.id()), null, after, null);
        return after;
    }

    @Transactional
    public OrderFulfillmentView submit(long tenantId, CurrentUser user, long orderId, OrderReviewRequest request) {
        Order before = requireVisibleOrder(tenantId, user, orderId);
        if (!"DRAFT".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅 DRAFT 订单可提交待支付");
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

    private PreparedItem prepareItem(long tenantId, CurrentUser user, OrderItemRequest request, LocalDate validTo) {
        PackageVersionSnapshot snapshot = catalogService.snapshotBySku(tenantId, user, request.skuId());
        Sku sku = snapshot.skus().stream().filter(item -> item.id().equals(request.skuId())).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在"));
        if (!"ACTIVE".equals(sku.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 未上架，不能创建订单");
        }
        if (!snapshot.entitlementIssuable()) {
            if ("PENDING_Q01".equals(snapshot.rulesStatus())) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "Q01 选择规则未确认，只能保存模板，不能发放权益",
                        List.of(new ApiResponse.ErrorDetail("Q01", "请业务确认选择组合、重复规则和每项次数后再下单")));
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "套餐权益配置不完整，不能创建订单");
        }
        int quantity = request.quantity();
        long listAmount = Math.multiplyExact(sku.salePriceFen(), quantity);
        List<Map<String, Object>> projectSnapshots = snapshot.projects().stream().map(project -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", project.projectId());
            item.put("projectName", project.projectName());
            item.put("grantCount", Math.multiplyExact(project.grantCount(), quantity));
            item.put("allocationPriceFen", project.allocationPriceFen());
            item.put("optionGroup", project.optionGroup());
            return item;
        }).toList();
        Map<String, Object> entitlementSnapshot = new LinkedHashMap<>();
        entitlementSnapshot.put("packageVersionId", snapshot.id());
        entitlementSnapshot.put("packageCode", snapshot.packageCode());
        entitlementSnapshot.put("packageName", snapshot.packageName());
        entitlementSnapshot.put("versionNo", snapshot.versionNo());
        entitlementSnapshot.put("selectionMode", snapshot.selectionMode());
        entitlementSnapshot.put("choiceCount", snapshot.choiceCount());
        entitlementSnapshot.put("selectionRules", snapshot.selectionRules());
        entitlementSnapshot.put("projectSnapshots", projectSnapshots);
        entitlementSnapshot.put("institutionIds", snapshot.institutions().stream().map(item -> item.id()).toList());
        entitlementSnapshot.put("validFrom", LocalDate.now(ZoneOffset.UTC).toString());
        entitlementSnapshot.put("validTo", validTo == null ? null : validTo.toString());
        entitlementSnapshot.put("allocationStatus", snapshot.allocationStatus());
        long weight = snapshot.projects().stream().mapToLong(item -> item.allocationPriceFen() == null ? 0L
                : Math.multiplyExact(item.allocationPriceFen(), quantity)).sum();
        return new PreparedItem(null, sku.id(), snapshot.id(), sku.skuCode(),
                snapshot.packageName() + "｜" + sku.specName(), quantity, listAmount, 0L,
                entitlementSnapshot, weight, snapshot.allocationStatus());
    }

    private void allocatePayable(long payable, List<PreparedItem> items) {
        if (items.stream().anyMatch(item -> "PENDING_Q02".equals(item.allocationStatus()))) {
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
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Q02 分摊权重必须大于 0");
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
        repository.insertStatusHistory(new OrderStatusHistory(null, before.id(), before.status(), toStatus,
                user.userId(), trigger, triggerRef, reason, null));
    }

    private record PreparedItem(Long id, Long skuId, Long packageVersionId, String skuCodeSnapshot,
                                String skuNameSnapshot, Integer purchaseQuantity, Long listAmountFen,
                                Long paidAllocatedFen, Map<String, Object> entitlementSnapshot,
                                long weight, String allocationStatus) {
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
