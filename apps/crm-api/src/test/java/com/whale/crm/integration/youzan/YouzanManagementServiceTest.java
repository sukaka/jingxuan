package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.application.YouzanRiskLinkOperations;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.PaymentTxn;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class YouzanManagementServiceTest {
    private static final long TENANT_ID = 1L;
    private static final String SHOP = "UNIT-YOUZAN-SHOP";

    private final YouzanRepository repository = mock(YouzanRepository.class);
    private final OrderApplicationService orderService = mock(OrderApplicationService.class);
    private final CatalogApplicationService catalogService = mock(CatalogApplicationService.class);
    private final CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final YouzanEventService eventService = mock(YouzanEventService.class);
    private final AesGcmCipher cipher = mock(AesGcmCipher.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final YouzanManagementService service = new YouzanManagementService(repository,
            mock(com.whale.crm.integration.youzan.domain.YouzanConfigRepository.class),
            eventService, mock(YouzanAdapterRouter.class), orderService,
            catalogService, scopeSupport, idempotencyService,
            auditService, mock(OutboxService.class), objectMapper, cipher,
            new SimpleTxManager());

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
        assertThat(view.externalCustomerId()).isEqualTo("external-customer-1");
        assertThat(view.orderTypeCode()).isEqualTo("REGULAR");
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).externalSkuId()).isEqualTo("301:9");
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
        when(scopeSupport.queryOf(DataScope.self(), manager))
                .thenReturn(new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 2L));
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1")).thenReturn(Optional.empty());

        when(repository.findLatestOrderEvents(eq(TENANT_ID), eq(SHOP),
                eq(new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 2L)), eq(0), eq(20))).thenReturn(List.of());
        when(repository.countDistinctExternalOrders(eq(TENANT_ID), eq(SHOP),
                eq(new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 2L)))).thenReturn(0L);
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

    @Test
    void ordersListSkipsInvisibleMappedAndRawOrdersWithout404CrashWhileDetailReturns404() {
        CurrentUser manager = user(2, "order:read");
        YouzanEvent mappedRaw = raw(51, "event-mapped-hidden", "ext-mapped-hidden", "PROCESSED", null, "{}");
        YouzanEvent linkedRaw = raw(52, "event-linked-hidden", "ext-linked-hidden", "MAPPING_FAILED", "err", "{}");
        when(scopeSupport.scope(manager)).thenReturn(DataScope.self());
        when(repository.findLatestOrderEvents(eq(TENANT_ID), eq(SHOP), any(), eq(0), eq(20)))
                .thenReturn(List.of(mappedRaw, linkedRaw));
        when(repository.countDistinctExternalOrders(eq(TENANT_ID), eq(SHOP), any())).thenReturn(0L);
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-mapped-hidden"))
                .thenReturn(Optional.of(new YouzanOrderMap(91L, SHOP, "ext-mapped-hidden", 701L, Instant.now(), null, 0L)));
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-linked-hidden")).thenReturn(Optional.empty());
        when(orderService.detail(TENANT_ID, manager, 701L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "订单不存在或不在数据范围内"));
        when(cipher.decrypt("cipher-raw-secret")).thenReturn(plainPayload("event-linked-hidden", "ext-linked-hidden"));
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1"))
                .thenReturn(Optional.of(new YouzanCustomerLink(81L, SHOP, "external-customer-1", 1010L,
                        "MANUAL", "HIGH", "test", 1L, null, 0L)));
        when(scopeSupport.requireVisible(TENANT_ID, 1010L, manager))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));

        PageResponse<YouzanOrderView> page = service.orders(TENANT_ID, manager, SHOP, 1, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();

        when(repository.findEvent(TENANT_ID, 51L)).thenReturn(Optional.of(mappedRaw));
        assertThatThrownBy(() -> service.order(TENANT_ID, manager, 51L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void emptyScopeAndScopedReadsEnforceDataScopeAcrossReportIssuesLinksAndRetry() {
        CurrentUser institutionUser = new CurrentUser(9L, TENANT_ID, "op1", "机构", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("order:read"), Set.of(5L));
        when(scopeSupport.scope(institutionUser)).thenReturn(DataScope.institution(Set.of(5L)));
        when(scopeSupport.isEmptyScope(DataScope.institution(Set.of(5L)))).thenReturn(true);

        YouzanReconcileReport emptyReport = service.report(TENANT_ID, institutionUser, SHOP);
        assertThat(emptyReport.totalEvents()).isZero();
        assertThat(emptyReport.internalOrders()).isZero();
        assertThat(emptyReport.openIssues()).isZero();
        assertThat(service.issues(TENANT_ID, institutionUser, null, 1, 20).items()).isEmpty();
        assertThat(service.skuMappings(TENANT_ID, institutionUser, SHOP)).isEmpty();
        assertThat(service.customerLinks(TENANT_ID, institutionUser, SHOP)).isEmpty();
        assertThatThrownBy(() -> service.retryIssue(institutionUser, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        CurrentUser teamManager = user(3L, "order:read", "campaign:write");
        DataScope teamScope = DataScope.team(Set.of(10L, 20L));
        when(scopeSupport.scope(teamManager)).thenReturn(teamScope);
        CustomerScopeSupport.ScopeQuery teamQuery = new CustomerScopeSupport.ScopeQuery("TEAM", List.of(10L, 20L), 3L);
        when(scopeSupport.queryOf(teamScope, teamManager)).thenReturn(teamQuery);
        when(repository.findScopedIssues(TENANT_ID, "OPEN", teamQuery, 0, 20)).thenReturn(List.of());
        when(repository.countScopedIssues(TENANT_ID, "OPEN", teamQuery)).thenReturn(0L);
        when(repository.findScopedCustomerLinks(TENANT_ID, SHOP, teamQuery)).thenReturn(List.of());

        assertThat(service.issues(TENANT_ID, teamManager, "OPEN", 1, 20).items()).isEmpty();
        assertThat(service.customerLinks(TENANT_ID, teamManager, SHOP)).isEmpty();
        verify(repository).findScopedIssues(TENANT_ID, "OPEN", teamQuery, 0, 20);
        verify(repository).findScopedCustomerLinks(TENANT_ID, SHOP, teamQuery);

        YouzanSyncIssue hiddenIssue = new YouzanSyncIssue(88L, 99L, "MAPPING_FAILED", "OPEN", "err",
                1, "err", null, Instant.now(), null, null, 0L);
        YouzanEvent hiddenEvent = raw(99L, "ev-hidden", "ord-hidden", "MAPPING_FAILED", "err", "{}");
        when(repository.findIssue(TENANT_ID, 88L)).thenReturn(Optional.of(hiddenIssue));
        when(repository.findEvent(TENANT_ID, 99L)).thenReturn(Optional.of(hiddenEvent));
        when(repository.findOrderMap(TENANT_ID, SHOP, "ord-hidden")).thenReturn(Optional.empty());
        when(cipher.decrypt("cipher-raw-secret")).thenReturn(plainPayload("ev-hidden", "ord-hidden"));
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryIssue(teamManager, 88L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void scopeQueryFallbackSortsTeamIdsDeterministically() {
        CurrentUser lead = user(4L, "order:read");
        DataScope reverseTeamScope = DataScope.team(new LinkedHashSet<>(List.of(20L, 10L)));
        when(scopeSupport.scope(lead)).thenReturn(reverseTeamScope);
        CustomerScopeSupport.ScopeQuery expected = new CustomerScopeSupport.ScopeQuery("TEAM", List.of(20L, 10L), 4L);
        assertThat(expected.teamIds()).containsExactly(10L, 20L);
        when(repository.findScopedIssues(TENANT_ID, "OPEN", expected, 0, 20)).thenReturn(List.of());
        when(repository.countScopedIssues(TENANT_ID, "OPEN", expected)).thenReturn(0L);
        when(repository.findScopedCustomerLinks(TENANT_ID, SHOP, expected)).thenReturn(List.of());

        assertThat(service.issues(TENANT_ID, lead, "OPEN", 1, 20).items()).isEmpty();
        assertThat(service.customerLinks(TENANT_ID, lead, SHOP)).isEmpty();
        verify(scopeSupport, times(2)).queryOf(reverseTeamScope, lead);
        verify(repository).findScopedIssues(TENANT_ID, "OPEN", expected, 0, 20);
        verify(repository).findScopedCustomerLinks(TENANT_ID, SHOP, expected);
    }

    @Test
    void ordersAsAllSkipsMappedOrderWhenOrderDetailNotFound() {
        CurrentUser admin = user(1L, "order:read", "integration:youzan:manage");
        YouzanEvent activeRaw = raw(61L, "event-active-1", "ext-active-1", "PROCESSED", null, "{}");
        YouzanEvent deletedCustomerRaw = raw(62L, "event-deleted-1", "ext-deleted-1", "PROCESSED", null, "{}");
        DataScope allScope = DataScope.all();
        CustomerScopeSupport.ScopeQuery allQuery = new CustomerScopeSupport.ScopeQuery("ALL", List.of(), 1L);
        when(scopeSupport.scope(admin)).thenReturn(allScope);
        when(scopeSupport.queryOf(allScope, admin)).thenReturn(allQuery);
        when(repository.findLatestOrderEvents(TENANT_ID, SHOP, allQuery, 0, 20))
                .thenReturn(List.of(activeRaw, deletedCustomerRaw));
        when(repository.countDistinctExternalOrders(TENANT_ID, SHOP, allQuery)).thenReturn(1L);
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-active-1"))
                .thenReturn(Optional.of(new YouzanOrderMap(92L, SHOP, "ext-active-1", 701L, Instant.now(), null, 0L)));
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-deleted-1"))
                .thenReturn(Optional.of(new YouzanOrderMap(93L, SHOP, "ext-deleted-1", 702L, Instant.now(), null, 0L)));
        when(cipher.decrypt("cipher-raw-secret")).thenReturn(plainPayload("event-active-1", "ext-active-1"));

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
        when(orderService.detail(TENANT_ID, admin, 701L)).thenReturn(fulfillment);
        when(orderService.detail(TENANT_ID, admin, 702L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));

        PageResponse<YouzanOrderView> page = service.orders(TENANT_ID, admin, SHOP, 1, 20);
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).eventId()).isEqualTo("event-active-1");

        when(repository.findEvent(TENANT_ID, 62L)).thenReturn(Optional.of(deletedCustomerRaw));
        assertThatThrownBy(() -> service.order(TENANT_ID, admin, 62L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void upsertSkuMappingAndCustomerLinkPassExplicitTenantIdTwo() {
        long tenantTwo = 2L;
        CurrentUser tenantTwoAdmin = new CurrentUser(201L, tenantTwo, "admin2", "二号租户管理员", "EMPLOYEE", 1L,
                Set.of("ADMIN"), Set.of("integration:youzan:manage", "campaign:write"), Set.of());
        when(idempotencyService.lookup(eq(tenantTwo), any(), any(), any())).thenReturn(Optional.empty());
        when(catalogService.snapshot(tenantTwo, tenantTwoAdmin, 301L))
                .thenReturn(new PackageVersionSnapshot(301L, 1L, "PKG-1", "套餐一", 1, "SINGLE", 1, null,
                        "ACTIVE", 0L, "COMPLETE", "COMPLETE", true, List.of(), List.of(), List.of(), List.of()));
        YouzanSkuMapping savedSku = new YouzanSkuMapping(11L, SHOP, "ext-sku-2", 301L, "ACTIVE", Instant.now(), 0L);
        when(repository.findSkuMapping(tenantTwo, SHOP, "ext-sku-2"))
                .thenReturn(Optional.empty(), Optional.of(savedSku));

        YouzanSkuMapping skuResult = service.upsertSkuMapping(tenantTwoAdmin,
                new YouzanSkuMappingRequest(SHOP, "ext-sku-2", 301L, null), "sku-t2-key");
        assertThat(skuResult.id()).isEqualTo(11L);
        verify(repository).insertSkuMapping(eq(tenantTwo), any(YouzanSkuMapping.class));

        YouzanCustomerLink savedLink = new YouzanCustomerLink(12L, SHOP, "ext-cust-2", 2010L, "MANUAL", "HIGH",
                "人工确认", 201L, Instant.now(), 0L);
        when(repository.findCustomerLink(tenantTwo, SHOP, "ext-cust-2"))
                .thenReturn(Optional.empty(), Optional.of(savedLink));

        YouzanCustomerLink linkResult = service.upsertCustomerLink(tenantTwoAdmin,
                new YouzanCustomerLinkRequest(SHOP, "ext-cust-2", 2010L, null, "人工确认"), "link-t2-key");
        assertThat(linkResult.id()).isEqualTo(12L);
        verify(scopeSupport).requireVisible(tenantTwo, 2010L, tenantTwoAdmin);
        verify(repository).insertCustomerLink(eq(tenantTwo), any(YouzanCustomerLink.class));
    }

    @Test
    void reportComputesAsiaShanghaiTodayUtcWindowAcrossUtcMidnight() {
        // 北京时间 2026-10-25 00:30:00 = UTC 2026-10-24T16:30:00Z（跨 UTC 0 点，与 UTC 日期差 1 天）
        YouzanManagementService beijingEarlyMorning = serviceWithClock(
                Clock.fixed(Instant.parse("2026-10-24T16:30:00Z"), ZoneOffset.UTC));
        Instant expectedStart = Instant.parse("2026-10-24T16:00:00Z");
        Instant expectedEndExclusive = Instant.parse("2026-10-25T16:00:00Z");

        CurrentUser admin = user(1L, "order:read", "integration:youzan:manage");
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findCursor(TENANT_ID, SHOP)).thenReturn(Optional.empty());
        when(repository.countEvents(TENANT_ID, SHOP)).thenReturn(10L);
        when(repository.countOrderMaps(TENANT_ID, SHOP)).thenReturn(8L);
        when(repository.countIssues(TENANT_ID, "OPEN")).thenReturn(1L);
        when(repository.countTodayEvents(TENANT_ID, SHOP, expectedStart, expectedEndExclusive)).thenReturn(3L);
        when(repository.countCampaignOrders(TENANT_ID, SHOP)).thenReturn(2L);

        YouzanReconcileReport adminReport = beijingEarlyMorning.report(TENANT_ID, admin, SHOP);
        assertThat(adminReport.todayEvents()).isEqualTo(3L);
        verify(repository).countTodayEvents(TENANT_ID, SHOP, expectedStart, expectedEndExclusive);

        CurrentUser manager = user(2L, "order:read");
        DataScope selfScope = DataScope.self();
        CustomerScopeSupport.ScopeQuery selfQuery = new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 2L);
        when(scopeSupport.scope(manager)).thenReturn(selfScope);
        when(scopeSupport.queryOf(selfScope, manager)).thenReturn(selfQuery);
        when(repository.countScopedEvents(TENANT_ID, SHOP, selfQuery)).thenReturn(5L);
        when(repository.countScopedOrderMaps(TENANT_ID, SHOP, selfQuery)).thenReturn(4L);
        when(repository.countScopedIssues(TENANT_ID, "OPEN", selfQuery)).thenReturn(1L);
        when(repository.countScopedTodayEvents(TENANT_ID, SHOP, selfQuery, expectedStart, expectedEndExclusive))
                .thenReturn(2L);
        when(repository.countScopedCampaignOrders(TENANT_ID, SHOP, selfQuery)).thenReturn(1L);

        YouzanReconcileReport managerReport = beijingEarlyMorning.report(TENANT_ID, manager, SHOP);
        assertThat(managerReport.todayEvents()).isEqualTo(2L);
        verify(repository).countScopedTodayEvents(TENANT_ID, SHOP, selfQuery, expectedStart, expectedEndExclusive);

        // 北京时间 2026-10-25 08:30:00 = UTC 2026-10-25T00:30:00Z，应与 00:30 属于同一业务日区间
        YouzanManagementService beijingMorning = serviceWithClock(
                Clock.fixed(Instant.parse("2026-10-25T00:30:00Z"), ZoneId.of("Asia/Shanghai")));
        beijingMorning.report(TENANT_ID, admin, SHOP);
        verify(repository, times(2)).countTodayEvents(TENANT_ID, SHOP, expectedStart, expectedEndExclusive);

        // 北京时间 2026-10-24 23:59:59.999 = UTC 2026-10-24T15:59:59.999Z，应落入前一个业务日
        Instant prevDayStart = Instant.parse("2026-10-23T16:00:00Z");
        Instant prevDayEndExclusive = Instant.parse("2026-10-24T16:00:00Z");
        when(repository.countTodayEvents(TENANT_ID, SHOP, prevDayStart, prevDayEndExclusive)).thenReturn(7L);
        YouzanManagementService beijingPrevDayLastMs = serviceWithClock(
                Clock.fixed(Instant.parse("2026-10-24T15:59:59.999Z"), ZoneId.of("Asia/Shanghai")));
        assertThat(beijingPrevDayLastMs.report(TENANT_ID, admin, SHOP).todayEvents()).isEqualTo(7L);
        verify(repository).countTodayEvents(TENANT_ID, SHOP, prevDayStart, prevDayEndExclusive);
    }

    @Test
    void upsertCustomerLinkNotifiesRiskLinkOperationsWhenUnlinkedIssuesExist() {
        YouzanRiskLinkOperations riskLinkOperations = mock(YouzanRiskLinkOperations.class);
        YouzanManagementService wiredService = new YouzanManagementService(repository,
                mock(com.whale.crm.integration.youzan.domain.YouzanConfigRepository.class),
                eventService, mock(YouzanAdapterRouter.class), orderService,
                catalogService, scopeSupport, idempotencyService,
                auditService, mock(OutboxService.class), objectMapper, cipher,
                new SimpleTxManager(), riskLinkOperations);
        CurrentUser admin = user(1L, "integration:youzan:manage");
        when(idempotencyService.lookup(eq(TENANT_ID), any(), any(), any())).thenReturn(Optional.empty());
        YouzanCustomerLink saved = new YouzanCustomerLink(15L, SHOP, "ext-cust-delay", 1010L, "MANUAL", "HIGH",
                "人工确认", 1L, Instant.now(), 0L);
        when(repository.findCustomerLink(TENANT_ID, SHOP, "ext-cust-delay"))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(repository.findIssueIdsWithoutOrderByExternalCustomer(TENANT_ID, SHOP, "ext-cust-delay"))
                .thenReturn(List.of(901L, 902L));

        wiredService.upsertCustomerLink(admin,
                new YouzanCustomerLinkRequest(SHOP, "ext-cust-delay", 1010L, null, "人工确认"), "link-delay-key");

        verify(riskLinkOperations).linkCustomerToYouzanIssues(TENANT_ID, List.of("901", "902"), 1010L);
    }

    private YouzanManagementService serviceWithClock(Clock clock) {
        return new YouzanManagementService(repository,
                mock(com.whale.crm.integration.youzan.domain.YouzanConfigRepository.class),
                eventService, mock(YouzanAdapterRouter.class), orderService,
                catalogService, scopeSupport, idempotencyService,
                auditService, mock(OutboxService.class), objectMapper, cipher,
                new SimpleTxManager(), clock);
    }

    private void stubList(YouzanEvent raw) {
        when(repository.findLatestOrderEvents(eq(TENANT_ID), eq(SHOP), any(), eq(0), eq(20))).thenReturn(List.of(raw));
        when(repository.countDistinctExternalOrders(eq(TENANT_ID), eq(SHOP), any())).thenReturn(1L);
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
                "order_type_code":"REGULAR","items":[{"external_sku_id":"301:9","quantity":2}],
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

    private static final class SimpleTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
