package com.whale.crm.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.campaign.application.CampaignApplicationService;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.trading.api.dto.CreateOrderRequest;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderApplicationServiceAttributionTest {

    @Test
    void legacyYouzanConstructorKeepsChannelEmpty() {
        CreateOrderRequest request = new CreateOrderRequest(1L, "PLATFORM", "REGULAR",
                null, null, 100L, null, List.of());

        assertThat(request.campaignId()).isNull();
        assertThat(request.campaignBatchId()).isNull();
        assertThat(request.channelId()).isNull();
    }

    @Test
    void externalSyncDoesNotIgnoreInvalidCampaignOrInsertOrder() {
        TradingRepository repository = mock(TradingRepository.class);
        CatalogApplicationService catalogService = mock(CatalogApplicationService.class);
        CampaignApplicationService campaignService = mock(CampaignApplicationService.class);
        DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        AuditService auditService = mock(AuditService.class);
        OrderApplicationService service = new OrderApplicationService(repository, catalogService, campaignService,
                dictionaryService, scopeSupport, auditService, new ObjectMapper());
        CurrentUser actor = new CurrentUser(1L, 1L, "sync", "同步账号", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        Customer customer = mock(Customer.class);
        when(scopeSupport.requireVisible(1L, 100L, actor)).thenReturn(customer);
        when(campaignService.resolveOrderAttribution(eq(1L), eq(11L), eq(22L), isNull(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "批次不存在或不属于所选活动"));
        CreateOrderRequest legacyRequest = new CreateOrderRequest(100L, "PLATFORM", "PROMOTION",
                11L, 22L, 100L, null, List.of());

        assertThatThrownBy(() -> service.createExternalOrder(1L, actor, legacyRequest,
                "YOUZAN", "shop", "external-order"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于所选活动");
        verify(repository, never()).insertOrder(any());
        verify(campaignService).resolveOrderAttribution(eq(1L), eq(11L), eq(22L), isNull(), any());
    }

    @Test
    void inactiveOrderTypeIsRejectedBeforeInsert() {
        TradingRepository repository = mock(TradingRepository.class);
        CatalogApplicationService catalogService = mock(CatalogApplicationService.class);
        CampaignApplicationService campaignService = mock(CampaignApplicationService.class);
        DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        AuditService auditService = mock(AuditService.class);
        OrderApplicationService service = new OrderApplicationService(repository, catalogService, campaignService,
                dictionaryService, scopeSupport, auditService, new ObjectMapper());
        CurrentUser actor = new CurrentUser(1L, 1L, "manager", "管家", "EMPLOYEE", null,
                Set.of("MANAGER"), Set.of("order:write"), Set.of());
        Customer customer = mock(Customer.class);
        when(scopeSupport.requireVisible(1L, 100L, actor)).thenReturn(customer);
        when(campaignService.resolveOrderAttribution(eq(1L), isNull(), isNull(), isNull(), any()))
                .thenReturn(com.whale.crm.campaign.domain.OrderAttributionSnapshot.empty());
        when(dictionaryService.requireActiveOption(1L, "orderTypes", "INACTIVE_TYPE"))
                .thenThrow(new BusinessException(ErrorCode.CONFLICT, "订单类型已停用"));
        CreateOrderRequest request = new CreateOrderRequest(100L, "PLATFORM", "INACTIVE_TYPE",
                null, null, 100L, null, List.of());

        assertThatThrownBy(() -> service.create(1L, actor, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单类型已停用");
        verify(repository, never()).insertOrder(any());
    }
}
