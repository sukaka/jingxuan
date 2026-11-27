package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.adapter.YouzanAnnualPullClient;
import com.whale.crm.annualreport.api.dto.CustomerYouzanHistoryOrdersResponse;
import com.whale.crm.annualreport.domain.YouzanAnnualOrderSnapshot;
import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户有赞历史订单只读查询服务：
 * - 强制执行租户隔离 + 内部员工限制 + CustomerScopeSupport 行级可见性（越权 404 不泄露存在性）；
 * - 联合检索 crm_youzan_customer_link / crm_customer_identity(YOUZAN) / crm_youzan_annual_profile
 *   关联的所有有赞身份并查询只读快照表 crm_youzan_annual_order_snapshot；
 * - 全程只读，绝不写入 crm_order 或触发权益发放，响应中不含明文 yz_open_id / 手机号 / 地址。
 */
@Service
public class CustomerYouzanHistoryOrderService {

    private static final TypeReference<List<YouzanAnnualPullClient.PulledOrderItem>> ITEMS_TYPE =
            new TypeReference<>() {
            };

    private final CustomerScopeSupport customerScopeSupport;
    private final YouzanAnnualSnapshotRepository snapshotRepository;
    private final QuickReplyTextMasker masker;
    private final ObjectMapper objectMapper;

    public CustomerYouzanHistoryOrderService(CustomerScopeSupport customerScopeSupport,
                                             YouzanAnnualSnapshotRepository snapshotRepository,
                                             QuickReplyTextMasker masker,
                                             ObjectMapper objectMapper) {
        this.customerScopeSupport = customerScopeSupport;
        this.snapshotRepository = snapshotRepository;
        this.masker = masker;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CustomerYouzanHistoryOrdersResponse listCustomerHistoryOrders(
            long tenantId, CurrentUser user, long customerId, boolean paidOnly, int page, int size) {
        if (user == null || user.isInstitutionUser() || !"EMPLOYEE".equals(user.userType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅内部员工可查看客户有赞历史订单");
        }
        customerScopeSupport.requireVisible(tenantId, customerId, user);

        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int offset = (safePage - 1) * safeSize;

        List<YouzanAnnualSnapshotRepository.LinkedYouzanIdentity> identities =
                snapshotRepository.findLinkedYouzanIdentitiesByCustomerId(tenantId, customerId);
        if (identities == null || identities.isEmpty()) {
            return new CustomerYouzanHistoryOrdersResponse(
                    customerId,
                    false,
                    List.of(),
                    CustomerYouzanHistoryOrdersResponse.Summary.empty(),
                    new PageResponse<>(List.of(), 0L, safePage, safeSize)
            );
        }

        Set<String> shopIds = new LinkedHashSet<>();
        for (YouzanAnnualSnapshotRepository.LinkedYouzanIdentity ident : identities) {
            if (ident != null && ident.shopId() != null && !ident.shopId().isBlank()) {
                shopIds.add(ident.shopId().trim());
            }
        }

        YouzanAnnualSnapshotRepository.CustomerOrderHistorySummaryRow rawSummary =
                snapshotRepository.summarizeOrdersByIdentities(tenantId, identities);
        CustomerYouzanHistoryOrdersResponse.Summary summary = rawSummary == null
                ? CustomerYouzanHistoryOrdersResponse.Summary.empty()
                : new CustomerYouzanHistoryOrdersResponse.Summary(
                        rawSummary.totalOrderCount(),
                        rawSummary.paidOrderCount(),
                        rawSummary.totalPaymentFen(),
                        rawSummary.totalRefundedFen(),
                        rawSummary.netSpendFen(),
                        rawSummary.firstOrderAt(),
                        rawSummary.lastOrderAt(),
                        rawSummary.lastSyncedAt());

        long total = snapshotRepository.countOrdersByIdentities(tenantId, identities, paidOnly);
        List<CustomerYouzanHistoryOrdersResponse.OrderItem> items = total == 0L
                ? List.of()
                : snapshotRepository.findOrdersByIdentities(tenantId, identities, paidOnly, offset, safeSize)
                        .stream()
                        .map(this::toOrderItem)
                        .toList();

        return new CustomerYouzanHistoryOrdersResponse(
                customerId,
                true,
                List.copyOf(shopIds),
                summary,
                new PageResponse<>(items, total, safePage, safeSize)
        );
    }

    private CustomerYouzanHistoryOrdersResponse.OrderItem toOrderItem(YouzanAnnualOrderSnapshot row) {
        long paymentFen = Math.max(0L, row.paymentFen());
        long refundedFen = Math.max(0L, row.refundedFen());
        long netPaymentFen = Math.max(0L, paymentFen - refundedFen);
        List<CustomerYouzanHistoryOrdersResponse.OrderLineItem> lines = parseOrderLines(row.itemsJson());
        String safeTitle = masker.mask(row.titleSummary(), 160);
        return new CustomerYouzanHistoryOrdersResponse.OrderItem(
                row.id(),
                row.shopId(),
                row.tid(),
                row.orderStatus(),
                row.paid(),
                row.orderedAt(),
                row.paidAt(),
                paymentFen,
                refundedFen,
                netPaymentFen,
                safeTitle,
                lines,
                row.syncedAt()
        );
    }

    private List<CustomerYouzanHistoryOrdersResponse.OrderLineItem> parseOrderLines(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            List<YouzanAnnualPullClient.PulledOrderItem> parsed = objectMapper.readValue(itemsJson, ITEMS_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }
            return parsed.stream()
                    .map(item -> new CustomerYouzanHistoryOrdersResponse.OrderLineItem(
                            item.itemId(),
                            item.skuId(),
                            masker.mask(item.title(), 120),
                            item.skuProps() == null ? null : masker.mask(item.skuProps(), 80),
                            Math.max(1, item.num()),
                            Math.max(0L, item.priceFen())
                    ))
                    .toList();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }
}
