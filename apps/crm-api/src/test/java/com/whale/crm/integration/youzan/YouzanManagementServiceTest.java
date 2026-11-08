package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.PaymentTxn;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class YouzanManagementServiceTest {
    private static final long TENANT_ID = 1L;
    private static final String SHOP = "UNIT-YOUZAN-SHOP";

    private final YouzanRepository repository = mock(YouzanRepository.class);
    private final OrderApplicationService orderService = mock(OrderApplicationService.class);
    private final CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
    private final AesGcmCipher cipher = mock(AesGcmCipher.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final YouzanManagementService service = new YouzanManagementService(repository,
            mock(com.whale.crm.integration.youzan.domain.YouzanConfigRepository.class),
            mock(YouzanEventService.class), mock(YouzanAdapterRouter.class), orderService,
            mock(CatalogApplicationService.class), scopeSupport, mock(IdempotencyService.class),
            mock(AuditService.class), mock(OutboxService.class), objectMapper, cipher,
            mock(PlatformTransactionManager.class));

    @Test
    void rawOnlyFailureReturnsSafeFieldsAndNeverLoadsInternalOrder() throws Exception {
        CurrentUser manager = user(2, "order:read");
        YouzanEvent raw = raw(41, "event-raw-1", "external-raw-1", "MAPPING_FAILED",
                "外部SKU未映射或数量无效: YZ-SKU-MISSING", maskedPayload());
        stubList(raw);
        when(cipher.decrypt("cipher-raw-secret")).thenReturn(plainPayload("event-raw-1", "external-raw-1"));
        when(scopeSupport.scope(manager)).thenReturn(DataScope.self());
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1"))
                .thenReturn(Optional.of(new YouzanCustomerLink(8L, SHOP, "external-customer-1", 1010L,
                        "MANUAL", "HIGH", "单元测试虚构映射", 1L, null, 0L)));

        PageResponse<YouzanOrderView> page = service.orders(TENANT_ID, manager, SHOP, 1, 20);

        assertThat(page.total()).isEqualTo(1);
        YouzanOrderView view = page.items().get(0);
        assertThat(view.eventId()).isEqualTo("event-raw-1");
        assertThat(view.mapId()).isNull();
        assertThat(view.orderId()).isNull();
        assertThat(view.status()).isEqualTo("MAPPING_FAILED");
        assertThat(view.mappingStatus()).isEqualTo("MAPPING_FAILED");
        assertThat(view.orderStatus()).isNull();
        assertThat(view.sourceUpdatedAt()).isEqualTo(Instant.parse("2026-10-24T04:00:00Z"));
        assertThat(view.externalAmountFen()).isEqualTo(181100);
        assertThat(view.paidFen()).isNull();
        assertThat(view.verificationMethod()).isNull();
        assertThat(view.sourceSystem()).isEqualTo("WEBHOOK");
        assertThat(view.externalShopId()).isEqualTo(SHOP);
        assertThat(view.payloadMaskedJson()).isNull();
        assertThat(view.errorReason()).contains("YZ-SKU-MISSING");
        assertThat(objectMapper.writeValueAsString(view))
                .doesNotContain("cipher-raw-secret", "13900000000", "raw-nickname", "raw-address", maskedPayload());
        verify(scopeSupport).requireVisible(TENANT_ID, 1010, manager);
        verifyNoInteractions(orderService);
    }

    @Test
    void maskedPayloadIsReturnedToYouzanManager() {
        CurrentUser admin = user(1, "order:read", "integration:youzan:manage");
        YouzanEvent raw = raw(44, "event-admin-1", "external-admin-1", "PROCESSED", null, maskedPayload());
        stubList(raw);
        when(cipher.decrypt("cipher-raw-secret")).thenReturn(plainPayload("event-admin-1", "external-admin-1"));
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());

        YouzanOrderView view = service.orders(TENANT_ID, admin, SHOP, 1, 20).items().get(0);

        assertThat(view.payloadMaskedJson()).isEqualTo(maskedPayload());
    }

    @Test
    void mappedOrderKeepsInternalStatusPaidAmountAndPaymentVerification() {
        CurrentUser admin = user(1);
        YouzanEvent raw = raw(42, "event-mapped-1", "external-mapped-1", "PROCESSED", null, "{}");
        stubList(raw);
        when(cipher.decrypt("cipher-raw-secret"))
                .thenReturn(plainPayload("event-mapped-1", "external-mapped-1"));
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findOrderMap(TENANT_ID, SHOP, "external-mapped-1"))
                .thenReturn(Optional.of(new YouzanOrderMap(9L, SHOP, "external-mapped-1", 71L,
                        Instant.parse("2026-10-24T04:00:00Z"), null, 0L)));

        OrderFulfillmentView fulfillment = mock(OrderFulfillmentView.class);
        OrderDetail orderDetail = mock(OrderDetail.class);
        Order order = mock(Order.class);
        PaymentTxn payment = mock(PaymentTxn.class);
        when(fulfillment.order()).thenReturn(orderDetail);
        when(orderDetail.order()).thenReturn(order);
        when(orderDetail.payments()).thenReturn(List.of(payment));
        when(order.status()).thenReturn("PAID");
        when(order.paidFen()).thenReturn(181100L);
        when(order.sourceSystem()).thenReturn("YOUZAN");
        when(order.externalShopId()).thenReturn(SHOP);
        when(payment.verificationMethod()).thenReturn("PROVIDER_CALLBACK");
        when(orderService.detail(TENANT_ID, admin, 71)).thenReturn(fulfillment);

        YouzanOrderView view = service.orders(TENANT_ID, admin, SHOP, 1, 20).items().get(0);

        assertThat(view.mapId()).isEqualTo(9);
        assertThat(view.orderId()).isEqualTo(71);
        assertThat(view.status()).isEqualTo("PROCESSED");
        assertThat(view.mappingStatus()).isEqualTo("PROCESSED");
        assertThat(view.orderStatus()).isEqualTo("PAID");
        assertThat(view.externalAmountFen()).isEqualTo(181100);
        assertThat(view.paidFen()).isEqualTo(181100);
        assertThat(view.verificationMethod()).isEqualTo("PROVIDER_CALLBACK");
        assertThat(view.sourceSystem()).isEqualTo("YOUZAN");
        assertThat(view.externalShopId()).isEqualTo(SHOP);
        verify(orderService).detail(TENANT_ID, admin, 71);
    }

    @Test
    void rawWithoutCustomerLinkIsHiddenOutsideAllScope() {
        CurrentUser manager = user(2);
        CurrentUser admin = user(1);
        YouzanEvent raw = raw(43, "event-unlinked-1", "external-unlinked-1", "CUSTOMER_UNRESOLVED",
                "客户尚未人工确认映射", "{}");
        stubList(raw);
        when(cipher.decrypt("cipher-raw-secret"))
                .thenReturn(plainPayload("event-unlinked-1", "external-unlinked-1"));
        when(scopeSupport.scope(manager)).thenReturn(DataScope.self());
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1")).thenReturn(Optional.empty());

        when(repository.findLatestOrderEvents(TENANT_ID, SHOP, true, 0, 20)).thenReturn(List.of());
        when(repository.countDistinctExternalOrders(TENANT_ID, SHOP, true)).thenReturn(0L);
        PageResponse<YouzanOrderView> managerPage = service.orders(TENANT_ID, manager, SHOP, 1, 20);
        assertThat(managerPage.items()).isEmpty();
        assertThat(managerPage.total()).isZero();
        when(repository.findEvent(TENANT_ID, 43)).thenReturn(Optional.of(raw));
        assertThatThrownBy(() -> service.order(TENANT_ID, manager, 43))
                .isInstanceOf(BusinessException.class)
                .hasMessage("订单不存在或不在数据范围内");
        YouzanOrderView adminView = service.orders(TENANT_ID, admin, SHOP, 1, 20).items().get(0);
        assertThat(adminView.eventId()).isEqualTo("event-unlinked-1");
        assertThat(adminView.orderId()).isNull();
        verifyNoInteractions(orderService);
    }

    private void stubList(YouzanEvent raw) {
        when(repository.findLatestOrderEvents(TENANT_ID, SHOP, false, 0, 20)).thenReturn(List.of(raw));
        when(repository.findLatestOrderEvents(TENANT_ID, SHOP, true, 0, 20)).thenReturn(List.of(raw));
        when(repository.countDistinctExternalOrders(TENANT_ID, SHOP, false)).thenReturn(1L);
        when(repository.countDistinctExternalOrders(TENANT_ID, SHOP, true)).thenReturn(1L);
        when(repository.findOrderMap(TENANT_ID, SHOP, raw.externalOrderNo())).thenReturn(Optional.empty());
    }

    private YouzanEvent raw(long id, String eventId, String externalOrderNo, String status,
                            String error, String masked) {
        return new YouzanEvent(id, SHOP, eventId, "ORDER_PAID", externalOrderNo, masked,
                "cipher-raw-secret", true, "WEBHOOK", status, 1, error, null,
                Instant.parse("2026-10-24T04:00:01Z"), null, null, 0L);
    }

    private String plainPayload(String eventId, String externalOrderNo) {
        return """
                {"event_id":"%s","event_type":"ORDER_PAID","shop_id":"%s",
                "external_order_no":"%s","external_customer_id":"external-customer-1",
                "amount_fen":181100,"external_updated_at":"2026-10-24T04:00:00Z",
                "mobile":"13900000000","nickname":"raw-nickname",
                "receiver":{"address":"raw-address"}}
                """.formatted(eventId, SHOP, externalOrderNo);
    }

    private String maskedPayload() {
        return "{\"mobile\":\"***\",\"nickname\":\"***\",\"receiver\":{\"address\":\"***\"}}";
    }

    private CurrentUser user(long userId, String... permissions) {
        return new CurrentUser(userId, TENANT_ID, "user-" + userId, "单元测试", "EMPLOYEE", 1L,
                Set.of(), Set.of(permissions), Set.of());
    }
}
