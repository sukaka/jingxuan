package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.trading.domain.Entitlement;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradingFulfillmentTimezoneTest {

    private static final Instant SHANGHAI_MIDNIGHT_CROSSING = Instant.parse("2026-11-21T16:30:00Z");

    @Test
    void appointmentAndRedemptionUseShanghaiDateAcrossMidnightUtcBoundary() {
        TradingRepository repository = mock(TradingRepository.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        OutboxService outboxService = mock(OutboxService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(SHANGHAI_MIDNIGHT_CROSSING, ZoneOffset.UTC);

        TradingFulfillmentApplicationService service = new TradingFulfillmentApplicationService(
                repository, scopeSupport, outboxService, objectMapper, clock);
        CurrentUser user = new CurrentUser(1L, 1L, "manager", "管家", "EMPLOYEE", null,
                Set.of("MANAGER"), Set.of("fulfillment:read"), Set.of());
        when(scopeSupport.scope(user)).thenReturn(DataScope.all());
        when(scopeSupport.queryOf(DataScope.all(), user))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("ALL", List.of(), 1L));

        Order paidOrder = new Order(10L, "J000000000010", 100L, null, 1L, 2L, null, "CRM", null,
                "REGULAR", null, null, "PAID", "PLATFORM", 1000L, 0L, 1000L, 1000L, 0L,
                SHANGHAI_MIDNIGHT_CROSSING, SHANGHAI_MIDNIGHT_CROSSING, SHANGHAI_MIDNIGHT_CROSSING, 0L);
        OrderItem orderItem = new OrderItem(20L, 10L, 301L, 301L, "SKU-301", "套餐", 1, 1000L, 1000L,
                "{\"projectSnapshots\":[{\"projectId\":101,\"grantCount\":2}],\"institutionIds\":[1],\"allocationStatus\":\"CONFIGURED\"}",
                0L);
        when(repository.findOrderItem(1L, 20L)).thenReturn(Optional.of(orderItem));
        when(repository.findOrder(1L, 10L)).thenReturn(Optional.of(paidOrder));

        Entitlement activeInShanghai = new Entitlement(1L, 100L, 20L, 101L, "基础水光", 2, 2, 0,
                LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 22), "AVAILABLE",
                SHANGHAI_MIDNIGHT_CROSSING, 0L);
        Entitlement expiredInShanghai = new Entitlement(2L, 100L, 20L, 101L, "基础水光", 2, 2, 0,
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21), "AVAILABLE",
                SHANGHAI_MIDNIGHT_CROSSING, 0L);
        when(repository.searchEntitlements(1L, "ALL", List.of(), 1L, 100L, "AVAILABLE", 0, 100))
                .thenReturn(List.of(activeInShanghai, expiredInShanghai));
        when(repository.findEntitlement(1L, 1L)).thenReturn(Optional.of(activeInShanghai));
        when(repository.findEntitlement(1L, 2L)).thenReturn(Optional.of(expiredInShanghai));
        when(repository.findOrdersForUpdate(1L, List.of(10L))).thenReturn(List.of(paidOrder));
        when(repository.findEntitlementsByOrderIdsForUpdate(1L, List.of(10L)))
                .thenReturn(List.of(activeInShanghai, expiredInShanghai));

        assertThat(service.appointmentOptions(1L, user, 100L))
                .extracting(view -> view.entitlement().id())
                .containsExactly(1L);
        assertThat(service.requireAppointmentEntitlement(1L, 1L, 100L, 1L, 1, SHANGHAI_MIDNIGHT_CROSSING)
                .entitlement().id()).isEqualTo(1L);
        assertThat(service.lockRedeemable(1L, 1L, 100L, 1L, 101L, 1, SHANGHAI_MIDNIGHT_CROSSING)
                .entitlement().id()).isEqualTo(1L);

        assertThatThrownBy(() -> service.lockRedeemable(1L, 2L, 100L, 1L, 101L, 1, SHANGHAI_MIDNIGHT_CROSSING))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> service.lockReversible(1L, 2L, 100L, 101L, "SRC-1", 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
