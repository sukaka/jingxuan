package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.campaign.domain.OrderAttributionSnapshot;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.api.dto.DictionaryOptionResponse;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Institution;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.api.dto.OrderItemRequest;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEntitlementTimezoneTest {

    private static final Instant SHANGHAI_MIDNIGHT_CROSSING = Instant.parse("2026-11-21T16:30:00Z");

    @Test
    void createUsesShanghaiDateForValidFromAndValidToComparison() throws Exception {
        TradingRepository repository = mock(TradingRepository.class);
        CatalogApplicationService catalogService = mock(CatalogApplicationService.class);
        CampaignApplicationService campaignService = mock(CampaignApplicationService.class);
        DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Clock clock = Clock.fixed(SHANGHAI_MIDNIGHT_CROSSING, ZoneOffset.UTC);

        OrderApplicationService service = new OrderApplicationService(repository, catalogService, campaignService,
                dictionaryService, scopeSupport, auditService, objectMapper, clock);
        CurrentUser actor = new CurrentUser(1L, 1L, "manager", "管家", "EMPLOYEE", null,
                Set.of("MANAGER"), Set.of("order:write"), Set.of());
        Customer customer = new Customer();
        customer.setId(100L);
        customer.setOwnerStaffId(2L);
        when(scopeSupport.requireVisibleForUpdate(1L, 100L, actor)).thenReturn(customer);
        when(scopeSupport.requireVisible(1L, 100L, actor)).thenReturn(customer);
        when(campaignService.resolveOrderAttribution(eq(1L), any(), any(), any(), eq(SHANGHAI_MIDNIGHT_CROSSING)))
                .thenReturn(OrderAttributionSnapshot.empty());
        when(dictionaryService.requireActiveOption(1L, "orderTypes", "REGULAR"))
                .thenReturn(new DictionaryOptionResponse("REGULAR", "常规订单", 1));
        when(catalogService.snapshotBySku(1L, actor, 301L)).thenReturn(activeSnapshot());

        Order persistedOrder = new Order(88L, "J000000000088", 100L, null, 1L, 2L, null, "CRM", null,
                "REGULAR", null, null, "DRAFT", "PLATFORM", 1000L, 0L, 1000L, 0L, 0L,
                SHANGHAI_MIDNIGHT_CROSSING, null, SHANGHAI_MIDNIGHT_CROSSING, 0L);
        when(repository.findOrderByNo(eq(1L), anyString())).thenReturn(Optional.of(persistedOrder));
        when(repository.findOrder(1L, 88L)).thenReturn(Optional.of(persistedOrder));
        when(repository.findOrderItems(1L, 88L)).thenReturn(List.of(
                new OrderItem(99L, 88L, 301L, 301L, "SKU-301", "套餐｜规格", 1, 1000L, 1000L, "{}", 0L)));
        when(repository.findEntitlementsByOrderItemIds(1L, List.of(99L))).thenReturn(List.of());
        when(repository.findPaymentsByOrder(1L, 88L)).thenReturn(List.of());
        when(repository.findStatusHistory(1L, 88L)).thenReturn(List.of());

        CreateOrderRequest validRequest = new CreateOrderRequest(100L, "PLATFORM", "REGULAR",
                null, null, 1000L, LocalDate.of(2026, 11, 22), List.of(new OrderItemRequest(301L, 1)));
        service.create(1L, actor, validRequest);

        ArgumentCaptor<OrderItem> itemCaptor = ArgumentCaptor.forClass(OrderItem.class);
        verify(repository).insertOrderItem(eq(1L), itemCaptor.capture());
        JsonNode snapshot = objectMapper.readTree(itemCaptor.getValue().entitlementSnapshot());
        assertThat(snapshot.path("validFrom").asText()).isEqualTo("2026-11-22");
        assertThat(snapshot.path("validTo").asText()).isEqualTo("2026-11-22");

        verify(repository, times(1)).insertOrder(anyLong(), any());

        CreateOrderRequest yesterdayInShanghai = new CreateOrderRequest(100L, "PLATFORM", "REGULAR",
                null, null, 1000L, LocalDate.of(2026, 11, 21), List.of(new OrderItemRequest(301L, 1)));
        assertThatThrownBy(() -> service.create(1L, actor, yesterdayInShanghai))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repository, times(1)).insertOrder(anyLong(), any());
    }

    private PackageVersionSnapshot activeSnapshot() {
        Sku sku = new Sku(301L, "SKU-301", 301L, 201L, "套餐", 1, "FIXED", "规格", 1000L,
                "ACTIVE", Instant.now(), null, Instant.now(), 0L);
        PackageProject project = new PackageProject(1L, 301L, 101L, "PRJ-101", "基础水光", "CAT", 1000L,
                "ACTIVE", 1, 1, 1000L, "MAIN", 1000L, 0L);
        Institution inst = new Institution(1L, "INST-1", "示范机构", "示范", "BJ", "ACTIVE", 0L);
        return new PackageVersionSnapshot(301L, 201L, "PKG-1", "套餐", 1, "FIXED", 0, null, "ACTIVE", 0L,
                "READY", "CONFIGURED", true, List.of(project), List.of(sku), List.of(inst), List.of());
    }
}
