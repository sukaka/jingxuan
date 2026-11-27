package com.whale.crm.annualreport.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.annualreport.api.dto.CustomerYouzanHistoryOrdersResponse;
import com.whale.crm.annualreport.domain.YouzanAnnualOrderSnapshot;
import com.whale.crm.annualreport.domain.YouzanAnnualSnapshotRepository;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerYouzanHistoryOrderServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long CUSTOMER_ID = 88L;
    private static final CurrentUser MANAGER = new CurrentUser(
            2L, TENANT_ID, "manager1", "王丽", "EMPLOYEE", 1L,
            Set.of("MANAGER"), Set.of("customer:read"), Set.of());
    private static final CurrentUser INSTITUTION = new CurrentUser(
            8L, TENANT_ID, "inst1", "合作机构", "INSTITUTION", null,
            Set.of("INSTITUTION_STAFF"), Set.of("customer:read"), Set.of(1L));

    private CustomerScopeSupport scopeSupport;
    private YouzanAnnualSnapshotRepository snapshotRepository;
    private CustomerYouzanHistoryOrderService service;

    @BeforeEach
    void setUp() {
        scopeSupport = mock(CustomerScopeSupport.class);
        snapshotRepository = mock(YouzanAnnualSnapshotRepository.class);
        service = new CustomerYouzanHistoryOrderService(
                scopeSupport, snapshotRepository, new QuickReplyTextMasker(), new ObjectMapper());
    }

    @Test
    void rejectsInstitutionUserAndEnforcesCustomerScope() {
        assertThatThrownBy(() -> service.listCustomerHistoryOrders(TENANT_ID, INSTITUTION, CUSTOMER_ID, true, 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        when(scopeSupport.requireVisible(TENANT_ID, 999L, MANAGER))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
        assertThatThrownBy(() -> service.listCustomerHistoryOrders(TENANT_ID, MANAGER, 999L, true, 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void returnsEmptyUnlinkedResponseWhenCustomerHasNoYouzanIdentities() {
        when(scopeSupport.requireVisible(TENANT_ID, CUSTOMER_ID, MANAGER)).thenReturn(new Customer());
        when(snapshotRepository.findLinkedYouzanIdentitiesByCustomerId(TENANT_ID, CUSTOMER_ID))
                .thenReturn(List.of());

        CustomerYouzanHistoryOrdersResponse resp = service.listCustomerHistoryOrders(
                TENANT_ID, MANAGER, CUSTOMER_ID, true, 1, 20);

        assertThat(resp.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(resp.hasYouzanIdentity()).isFalse();
        assertThat(resp.linkedShopIds()).isEmpty();
        assertThat(resp.summary().paidOrderCount()).isZero();
        assertThat(resp.orders().items()).isEmpty();
        verify(snapshotRepository, never()).summarizeOrdersByIdentities(eq(TENANT_ID), anyList());
        verify(snapshotRepository, never()).findOrdersByIdentities(eq(TENANT_ID), anyList(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void returnsAggregatedSummaryAndParsedMaskedOrdersWithoutLeakingOpenId() {
        when(scopeSupport.requireVisible(TENANT_ID, CUSTOMER_ID, MANAGER)).thenReturn(new Customer());
        var identities = List.of(
                new YouzanAnnualSnapshotRepository.LinkedYouzanIdentity("40880001", "yz_open_secret_88"));
        when(snapshotRepository.findLinkedYouzanIdentitiesByCustomerId(TENANT_ID, CUSTOMER_ID))
                .thenReturn(identities);

        Instant firstAt = Instant.parse("2025-05-10T04:00:00Z");
        Instant lastAt = Instant.parse("2026-08-20T09:30:00Z");
        Instant syncedAt = Instant.parse("2026-11-27T01:00:00Z");
        when(snapshotRepository.summarizeOrdersByIdentities(TENANT_ID, identities))
                .thenReturn(new YouzanAnnualSnapshotRepository.CustomerOrderHistorySummaryRow(
                        3, 2, 466000L, 66000L, 400000L, firstAt, lastAt, syncedAt));
        when(snapshotRepository.countOrdersByIdentities(TENANT_ID, identities, true)).thenReturn(2L);

        String itemsJson = """
                [
                  {"itemId":"101","skuId":"201","title":"超光子嫩肤联系13800138000","skuProps":"全模式","num":2,"priceFen":168000}
                ]
                """;
        YouzanAnnualOrderSnapshot orderRow = new YouzanAnnualOrderSnapshot(
                10L, TENANT_ID, "40880001", "E202608200001", "yz_open_secret_88",
                "TRADE_SUCCESS", true, lastAt.minusSeconds(60), lastAt,
                268000L, 66000L, "超光子嫩肤 13800138000", itemsJson, syncedAt, syncedAt, syncedAt);
        when(snapshotRepository.findOrdersByIdentities(TENANT_ID, identities, true, 0, 20))
                .thenReturn(List.of(orderRow));

        CustomerYouzanHistoryOrdersResponse resp = service.listCustomerHistoryOrders(
                TENANT_ID, MANAGER, CUSTOMER_ID, true, 1, 20);

        assertThat(resp.hasYouzanIdentity()).isTrue();
        assertThat(resp.linkedShopIds()).containsExactly("40880001");
        assertThat(resp.summary().totalOrderCount()).isEqualTo(3);
        assertThat(resp.summary().paidOrderCount()).isEqualTo(2);
        assertThat(resp.summary().totalPaymentFen()).isEqualTo(466000L);
        assertThat(resp.summary().totalRefundedFen()).isEqualTo(66000L);
        assertThat(resp.summary().netSpendFen()).isEqualTo(400000L);
        assertThat(resp.orders().total()).isEqualTo(2L);
        assertThat(resp.orders().items()).hasSize(1);
        var item = resp.orders().items().get(0);
        assertThat(item.tid()).isEqualTo("E202608200001");
        assertThat(item.netPaymentFen()).isEqualTo(202000L);
        assertThat(item.titleSummary()).doesNotContain("13800138000");
        assertThat(item.items()).hasSize(1);
        assertThat(item.items().get(0).title()).doesNotContain("13800138000");
        assertThat(item.items().get(0).num()).isEqualTo(2);
    }
}
