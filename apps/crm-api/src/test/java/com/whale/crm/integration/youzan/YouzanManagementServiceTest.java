package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanBatchRetryResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanCustomerLinkRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderItemView;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderView;
import com.whale.crm.integration.youzan.api.dto.YouzanReconcileReport;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuCandidateView;
import com.whale.crm.integration.youzan.api.dto.YouzanSkuMappingRequest;
import com.whale.crm.integration.youzan.application.YouzanEventService;
import com.whale.crm.integration.youzan.application.YouzanManagementService;
import com.whale.crm.integration.youzan.application.YouzanRiskLinkOperations;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanMappingIssueEvent;
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
import com.whale.crm.product.domain.Sku;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import com.whale.crm.trading.domain.OrderItem;
import com.whale.crm.trading.domain.PaymentTxn;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.Mockito.never;
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

    @Test
    void skuCandidatesAggregatesMetadataEnforcesScopeAndExcludesPartiallyMappedEventsFromReadyList() throws Exception {
        CurrentUser admin = user(1L, "order:read", "integration:youzan:manage");
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, "5505:100", 301L, "ACTIVE", Instant.now(), 0L),
                new YouzanSkuMapping(2L, SHOP, "5505:200", 302L, "INACTIVE", Instant.now(), 0L)
        ));

        String realProvider1 = """
                {"full_order_info":{"order_info":{"tid":"E-YZ-1"},"buyer_info":{"fans_nickname":"***","buyer_phone":"***"},
                "orders":[{"item_id":5505,"sku_id":200,"title":"超光子嫩肤","sku_properties_name":"[{\\"k\\":\\"疗程\\",\\"v\\":\\"3次卡\\"},{\\"k\\":\\"门店\\",\\"v\\":\\"通用\\"}]","price":"1811.00","outer_sku_id":"CRM-OPT-3"}]}}
                """;
        String realProvider2 = """
                {"success":true,"code":200,"data":{"full_order_info":{"orders":[
                  {"item_id":5505,"sku_id":100,"title":"水光体验","sku_properties_name":"[{\\"k\\":\\"规格\\",\\"v\\":\\"1次\\"}]","price":"599.50"},
                  {"item_id":5505,"sku_id":200,"title":"超光子嫩肤","sku_properties_name":"[{\\"k\\":\\"疗程\\",\\"v\\":\\"3次卡\\"}]","price":"1811.00"}
                ]}}}
                """;
        String realProvider3 = """
                {"full_order_info":{"orders":[{"item_id":5505,"sku_id":100,"title":"水光体验","sku_properties_name":"单次","price":"599.50"}]}}
                """;

        YouzanMappingIssueEvent row1 = new YouzanMappingIssueEvent(101L, 201L, SHOP, "E-YZ-1",
                "{}", "cipher-1", realProvider1, Instant.parse("2026-11-26T10:00:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent row2 = new YouzanMappingIssueEvent(102L, 202L, SHOP, "E-YZ-2",
                "{}", "cipher-2", realProvider2, Instant.parse("2026-11-26T11:00:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent row3 = new YouzanMappingIssueEvent(103L, 203L, SHOP, "E-YZ-3",
                "{}", "cipher-3", realProvider3, Instant.parse("2026-11-26T12:00:00Z"), "OPEN", 0L);

        when(repository.findOpenMappingIssueEvents(TENANT_ID, SHOP, null, 500))
                .thenReturn(List.of(row1, row2, row3));
        when(cipher.decrypt("cipher-1")).thenReturn(plainPayloadWithSkus("ev-1", "E-YZ-1", List.of("5505:200")));
        when(cipher.decrypt("cipher-2")).thenReturn(plainPayloadWithSkus("ev-2", "E-YZ-2", List.of("5505:100", "5505:200")));
        when(cipher.decrypt("cipher-3")).thenReturn(plainPayloadWithSkus("ev-3", "E-YZ-3", List.of("5505:100")));

        List<YouzanSkuCandidateView> candidates = service.skuCandidates(TENANT_ID, admin, SHOP);

        assertThat(candidates).hasSize(2);
        // 未映射排第一，INACTIVE 视为未映射；统计涵盖 row1 + row2 共 2 条异常
        YouzanSkuCandidateView unmapped = candidates.get(0);
        assertThat(unmapped.externalSkuId()).isEqualTo("5505:200");
        assertThat(unmapped.mapped()).isFalse();
        assertThat(unmapped.crmSkuId()).isNull();
        assertThat(unmapped.pendingIssueCount()).isEqualTo(2);
        assertThat(unmapped.itemTitle()).isEqualTo("超光子嫩肤");
        assertThat(unmapped.skuSpec()).isEqualTo("疗程：3次卡 / 门店：通用");
        assertThat(unmapped.priceFen()).isEqualTo(181100L);
        assertThat(unmapped.outerSkuCode()).isEqualTo("CRM-OPT-3");
        assertThat(unmapped.sampleOrderNo()).isEqualTo("E-YZ-2");

        // 已映射的 5505:100 只计入全项已映射可重试的 row3（排除仍缺 5505:200 的 row2）
        YouzanSkuCandidateView ready = candidates.get(1);
        assertThat(ready.externalSkuId()).isEqualTo("5505:100");
        assertThat(ready.mapped()).isTrue();
        assertThat(ready.crmSkuId()).isEqualTo(301L);
        assertThat(ready.pendingIssueCount()).isEqualTo(1);
        assertThat(ready.itemTitle()).isEqualTo("水光体验");
        assertThat(ready.skuSpec()).isEqualTo("单次");
        assertThat(ready.priceFen()).isEqualTo(59950L);
        assertThat(ready.sampleOrderNo()).isEqualTo("E-YZ-3");
        assertThat(objectMapper.writeValueAsString(candidates))
                .doesNotContain("cipher-1", "13900000000", "raw-nickname", "raw-address");

        // 机构用户返回空列表；非 manage 团队用户走 scoped 查询
        CurrentUser institutionUser = new CurrentUser(9L, TENANT_ID, "op1", "机构", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("order:read"), Set.of(5L));
        assertThat(service.skuCandidates(TENANT_ID, institutionUser, SHOP)).isEmpty();

        CurrentUser teamUser = user(3L, "order:read");
        DataScope teamScope = DataScope.team(Set.of(10L));
        CustomerScopeSupport.ScopeQuery teamQuery = new CustomerScopeSupport.ScopeQuery("TEAM", List.of(10L), 3L);
        when(scopeSupport.scope(teamUser)).thenReturn(teamScope);
        when(scopeSupport.queryOf(teamScope, teamUser)).thenReturn(teamQuery);
        when(repository.findOpenMappingIssueEvents(TENANT_ID, SHOP, teamQuery, 500)).thenReturn(List.of());
        assertThat(service.skuCandidates(TENANT_ID, teamUser, SHOP)).isEmpty();
        verify(repository).findOpenMappingIssueEvents(TENANT_ID, SHOP, teamQuery, 500);
    }

    @Test
    void batchRetryMappingIssuesRejectsUnmappedSkuAndClassifiesBatchOutcomes() {
        CurrentUser admin = user(1L, "integration:youzan:manage", "campaign:write");
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, "5505:100", 301L, "ACTIVE", Instant.now(), 0L)
        ));

        assertThatThrownBy(() -> service.batchRetryMappingIssues(admin,
                new YouzanBatchRetryRequest(SHOP, "5505:999", 50), "batch-key-invalid"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        YouzanMappingIssueEvent ready1 = new YouzanMappingIssueEvent(301L, 401L, SHOP, "E-1",
                "{}", "c-ready-1", null, Instant.parse("2026-11-26T10:00:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent ready2 = new YouzanMappingIssueEvent(302L, 402L, SHOP, "E-2",
                "{}", "c-ready-2", null, Instant.parse("2026-11-26T10:01:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent ready3Conflict = new YouzanMappingIssueEvent(303L, 403L, SHOP, "E-3",
                "{}", "c-ready-3", null, Instant.parse("2026-11-26T10:02:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent ready4Remaining = new YouzanMappingIssueEvent(304L, 404L, SHOP, "E-4",
                "{}", "c-ready-4", null, Instant.parse("2026-11-26T10:03:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent partialMultiSku = new YouzanMappingIssueEvent(305L, 405L, SHOP, "E-5",
                "{}", "c-partial", null, Instant.parse("2026-11-26T10:04:00Z"), "OPEN", 0L);

        when(repository.findOpenMappingIssueEvents(TENANT_ID, SHOP, null, 500))
                .thenReturn(List.of(ready1, ready2, ready3Conflict, ready4Remaining, partialMultiSku));
        when(cipher.decrypt("c-ready-1")).thenReturn(plainPayloadWithSkus("ev-1", "E-1", List.of("5505:100")));
        when(cipher.decrypt("c-ready-2")).thenReturn(plainPayloadWithSkus("ev-2", "E-2", List.of("5505:100")));
        when(cipher.decrypt("c-ready-3")).thenReturn(plainPayloadWithSkus("ev-3", "E-3", List.of("5505:100")));
        when(cipher.decrypt("c-ready-4")).thenReturn(plainPayloadWithSkus("ev-4", "E-4", List.of("5505:100")));
        when(cipher.decrypt("c-partial")).thenReturn(plainPayloadWithSkus("ev-5", "E-5", List.of("5505:100", "5505:UNMAPPED")));

        YouzanSyncIssue issue301 = new YouzanSyncIssue(301L, 401L, "MAPPING_FAILED", "OPEN", "err", 1, "err", null, Instant.now(), null, null, 0L);
        YouzanSyncIssue issue302 = new YouzanSyncIssue(302L, 402L, "MAPPING_FAILED", "OPEN", "err", 1, "err", null, Instant.now(), null, null, 0L);
        YouzanSyncIssue issue303Resolved = new YouzanSyncIssue(303L, 403L, "MAPPING_FAILED", "RESOLVED", "err", 1, null, null, Instant.now(), Instant.now(), 1L, 1L);
        when(repository.findIssueForUpdate(TENANT_ID, 301L)).thenReturn(Optional.of(issue301));
        when(repository.findIssueForUpdate(TENANT_ID, 302L)).thenReturn(Optional.of(issue302));
        when(repository.findIssueForUpdate(TENANT_ID, 303L)).thenReturn(Optional.of(issue303Resolved));
        when(repository.updateIssueStatus(eq(TENANT_ID), eq(301L), eq(0L), eq("RETRYING"), eq(2), any(), any(), any(), any())).thenReturn(1);
        when(repository.updateIssueStatus(eq(TENANT_ID), eq(302L), eq(0L), eq("RETRYING"), eq(2), any(), any(), any(), any())).thenReturn(1);

        when(eventService.process(TENANT_ID, 401L)).thenReturn(new YouzanEventResponse(401L, "ev-1", "ORDER_PAID",
                "E-1", "PROCESSED", 2, null, 701L, Instant.now(), Instant.now(), false));
        when(eventService.process(TENANT_ID, 402L)).thenReturn(new YouzanEventResponse(402L, "ev-2", "ORDER_PAID",
                "E-2", "CUSTOMER_UNRESOLVED", 2, "客户尚未人工确认映射", null, Instant.now(), Instant.now(), false));

        YouzanBatchRetryResponse res = service.batchRetryMappingIssues(admin,
                new YouzanBatchRetryRequest(SHOP, "5505:100", 3), "batch-key-1");

        assertThat(res.shopId()).isEqualTo(SHOP);
        assertThat(res.externalSkuId()).isEqualTo("5505:100");
        assertThat(res.matched()).isEqualTo(4);
        assertThat(res.attempted()).isEqualTo(2);
        assertThat(res.processed()).isEqualTo(1);
        assertThat(res.advanced()).isEqualTo(1);
        assertThat(res.stillFailed()).isZero();
        assertThat(res.skipped()).isEqualTo(1);
        assertThat(res.remaining()).isEqualTo(1);
        verify(eventService).process(TENANT_ID, 401L);
        verify(eventService).process(TENANT_ID, 402L);
        verify(auditService).record(eq(TENANT_ID), eq(1L), eq("YOUZAN_ISSUE_BATCH_RETRY"), eq("YOUZAN"),
                eq(SHOP), eq(null), eq(res), eq(null));
    }

    @Test
    void skuCandidatesAndBatchRetryExcludeInvalidLinesCorruptedPayloadsAndEnforceScopes() {
        CurrentUser admin = user(1L, "order:read", "integration:youzan:manage", "campaign:write");
        when(scopeSupport.scope(admin)).thenReturn(DataScope.all());
        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, " 5505:100 ", 301L, "ACTIVE", Instant.now(), 0L)
        ));

        YouzanMappingIssueEvent validRow = new YouzanMappingIssueEvent(501L, 601L, SHOP, "E-VALID",
                "{}", "c-valid", null, Instant.parse("2026-11-26T10:00:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent invalidQtyRow = new YouzanMappingIssueEvent(502L, 602L, SHOP, "E-BAD-QTY",
                "{}", "c-bad-qty", null, Instant.parse("2026-11-26T10:01:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent blankSkuRow = new YouzanMappingIssueEvent(503L, 603L, SHOP, "E-BLANK-SKU",
                "{}", "c-blank-sku", null, Instant.parse("2026-11-26T10:02:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent corruptRow = new YouzanMappingIssueEvent(504L, 604L, SHOP, "E-CORRUPT",
                "{}", "c-corrupt", null, Instant.parse("2026-11-26T10:03:00Z"), "OPEN", 0L);

        when(repository.findOpenMappingIssueEvents(TENANT_ID, SHOP, null, 500))
                .thenReturn(List.of(validRow, invalidQtyRow, blankSkuRow, corruptRow));
        when(cipher.decrypt("c-valid")).thenReturn(plainPayloadWithSkus("ev-valid", "E-VALID", List.of("5505:100")));
        when(cipher.decrypt("c-bad-qty")).thenReturn(plainPayloadWithItems("ev-bad-qty", "E-BAD-QTY",
                "{\"external_sku_id\":\"5505:100\",\"quantity\":1},{\"external_sku_id\":\"5505:100\",\"quantity\":0}"));
        when(cipher.decrypt("c-blank-sku")).thenReturn(plainPayloadWithItems("ev-blank-sku", "E-BLANK-SKU",
                "{\"external_sku_id\":\"5505:100\",\"quantity\":1},{\"external_sku_id\":\"   \",\"quantity\":1}"));
        when(cipher.decrypt("c-corrupt")).thenThrow(new IllegalArgumentException("bad ciphertext"));

        List<YouzanSkuCandidateView> candidates = service.skuCandidates(TENANT_ID, admin, SHOP);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).externalSkuId()).isEqualTo("5505:100");
        assertThat(candidates.get(0).mapped()).isTrue();
        assertThat(candidates.get(0).pendingIssueCount()).isEqualTo(1);
        assertThat(candidates.get(0).sampleOrderNo()).isEqualTo("E-VALID");

        YouzanSyncIssue issue501 = new YouzanSyncIssue(501L, 601L, "MAPPING_FAILED", "OPEN", "err",
                1, "err", null, Instant.now(), null, null, 0L);
        when(repository.findIssueForUpdate(TENANT_ID, 501L)).thenReturn(Optional.of(issue501));
        when(repository.updateIssueStatus(eq(TENANT_ID), eq(501L), eq(0L), eq("RETRYING"), eq(2),
                any(), any(), any(), any())).thenReturn(1);
        when(eventService.process(TENANT_ID, 601L)).thenReturn(new YouzanEventResponse(601L, "ev-valid", "ORDER_PAID",
                "E-VALID", "PROCESSED", 2, null, 801L, Instant.now(), Instant.now(), false));

        YouzanBatchRetryResponse adminRetry = service.batchRetryMappingIssues(admin,
                new YouzanBatchRetryRequest(SHOP, "5505:100", 50), "batch-invalid-line-key");
        assertThat(adminRetry.matched()).isEqualTo(1);
        assertThat(adminRetry.attempted()).isEqualTo(1);
        assertThat(adminRetry.processed()).isEqualTo(1);
        verify(eventService).process(TENANT_ID, 601L);
        verify(eventService, never()).process(TENANT_ID, 602L);
        verify(eventService, never()).process(TENANT_ID, 603L);
        verify(eventService, never()).process(TENANT_ID, 604L);

        // INSTITUTION 用户返回全零且不查异常列表、不触发重试
        CurrentUser institutionUser = new CurrentUser(9L, TENANT_ID, "op1", "机构", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("campaign:write"), Set.of(5L));
        YouzanBatchRetryResponse instRes = service.batchRetryMappingIssues(institutionUser,
                new YouzanBatchRetryRequest(SHOP, "5505:100", 50), "batch-inst-key");
        assertThat(instRes).isEqualTo(new YouzanBatchRetryResponse(SHOP, "5505:100", 0, 0, 0, 0, 0, 0, 0));

        // 空范围（NONE）用户返回全零且不查异常列表、不触发重试
        CurrentUser emptyScopeUser = user(6L, "campaign:write");
        DataScope noneScope = DataScope.none();
        when(scopeSupport.scope(emptyScopeUser)).thenReturn(noneScope);
        when(scopeSupport.isEmptyScope(noneScope)).thenReturn(true);
        YouzanBatchRetryResponse noneRes = service.batchRetryMappingIssues(emptyScopeUser,
                new YouzanBatchRetryRequest(SHOP, "5505:100", 50), "batch-none-key");
        assertThat(noneRes).isEqualTo(new YouzanBatchRetryResponse(SHOP, "5505:100", 0, 0, 0, 0, 0, 0, 0));

        // TEAM 范围用户（仅 campaign:write，无 manage）走 scoped 查询并在逐条重试时二次校验可见性
        CurrentUser teamUser = user(4L, "campaign:write");
        DataScope teamScope = DataScope.team(Set.of(10L));
        CustomerScopeSupport.ScopeQuery teamQuery = new CustomerScopeSupport.ScopeQuery("TEAM", List.of(10L), 4L);
        when(scopeSupport.scope(teamUser)).thenReturn(teamScope);
        when(scopeSupport.isEmptyScope(teamScope)).thenReturn(false);
        when(scopeSupport.queryOf(teamScope, teamUser)).thenReturn(teamQuery);

        YouzanMappingIssueEvent teamVisibleRow = new YouzanMappingIssueEvent(510L, 610L, SHOP, "E-TEAM-VIS",
                "{}", "c-team-vis", null, Instant.parse("2026-11-26T10:10:00Z"), "OPEN", 0L);
        YouzanMappingIssueEvent teamHiddenRow = new YouzanMappingIssueEvent(511L, 611L, SHOP, "E-TEAM-HID",
                "{}", "c-team-hid", null, Instant.parse("2026-11-26T10:11:00Z"), "OPEN", 0L);
        when(repository.findOpenMappingIssueEvents(TENANT_ID, SHOP, teamQuery, 500))
                .thenReturn(List.of(teamVisibleRow, teamHiddenRow));
        when(cipher.decrypt("c-team-vis")).thenReturn(plainPayloadWithSkus("ev-tv", "E-TEAM-VIS", List.of("5505:100")));
        when(cipher.decrypt("c-team-hid")).thenReturn(plainPayloadWithSkus("ev-th", "E-TEAM-HID", List.of("5505:100")));

        YouzanSyncIssue issue510 = new YouzanSyncIssue(510L, 610L, "MAPPING_FAILED", "OPEN", "err",
                1, "err", null, Instant.now(), null, null, 0L);
        YouzanSyncIssue issue511 = new YouzanSyncIssue(511L, 611L, "MAPPING_FAILED", "OPEN", "err",
                1, "err", null, Instant.now(), null, null, 0L);
        YouzanEvent ev610 = new YouzanEvent(610L, SHOP, "ev-tv", "ORDER_PAID", "E-TEAM-VIS", "{}",
                "c-team-vis", true, "PULL", "MAPPING_FAILED", 1, "err", null, Instant.now(), null, null, 0L);
        YouzanEvent ev611 = new YouzanEvent(611L, SHOP, "ev-th", "ORDER_PAID", "E-TEAM-HID", "{}",
                "c-team-hid", true, "PULL", "MAPPING_FAILED", 1, "err", null, Instant.now(), null, null, 0L);
        when(repository.findIssue(TENANT_ID, 510L)).thenReturn(Optional.of(issue510));
        when(repository.findIssue(TENANT_ID, 511L)).thenReturn(Optional.of(issue511));
        when(repository.findEvent(TENANT_ID, 610L)).thenReturn(Optional.of(ev610));
        when(repository.findEvent(TENANT_ID, 611L)).thenReturn(Optional.of(ev611));
        when(repository.findOrderMap(TENANT_ID, SHOP, "E-TEAM-VIS")).thenReturn(Optional.empty());
        when(repository.findOrderMap(TENANT_ID, SHOP, "E-TEAM-HID")).thenReturn(Optional.empty());
        when(repository.findCustomerLink(TENANT_ID, SHOP, "external-customer-1"))
                .thenReturn(Optional.of(new YouzanCustomerLink(20L, SHOP, "external-customer-1", 1010L,
                        "MANUAL", "HIGH", "team", 1L, null, 0L)))
                .thenReturn(Optional.empty());
        when(repository.findIssueForUpdate(TENANT_ID, 510L)).thenReturn(Optional.of(issue510));
        when(repository.updateIssueStatus(eq(TENANT_ID), eq(510L), eq(0L), eq("RETRYING"), eq(2),
                any(), any(), any(), any())).thenReturn(1);
        when(eventService.process(TENANT_ID, 610L)).thenReturn(new YouzanEventResponse(610L, "ev-tv", "ORDER_PAID",
                "E-TEAM-VIS", "PROCESSED", 2, null, 810L, Instant.now(), Instant.now(), false));

        YouzanBatchRetryResponse teamRes = service.batchRetryMappingIssues(teamUser,
                new YouzanBatchRetryRequest(SHOP, null, 50), "batch-team-key");
        assertThat(teamRes.matched()).isEqualTo(2);
        assertThat(teamRes.attempted()).isEqualTo(1);
        assertThat(teamRes.processed()).isEqualTo(1);
        assertThat(teamRes.skipped()).isEqualTo(1);
        verify(repository).findOpenMappingIssueEvents(TENANT_ID, SHOP, teamQuery, 500);
        verify(eventService).process(TENANT_ID, 610L);
        verify(eventService, never()).process(TENANT_ID, 611L);
    }

    @Test
    void orderViewsExtractFullProductMetadataEnrichCrmSkusAndRespectOrderReadMasking() throws Exception {
        CurrentUser reader = user(2L, "order:read");
        String longTitle = "超光子嫩肤套餐" + "A".repeat(230);
        String longOuterCode = "OUT-" + "X".repeat(140);
        String providerMasked = """
                {"full_order_info":{"order_info":{"tid":"ext-full-1"},
                "buyer_info":{"buyer_phone":"***","fans_nickname":"***"},
                "orders":[
                  {"item_id":5505,"sku_id":101,"num":2,"title":"%s",
                   "sku_properties_name":"[{\\"k\\":\\"疗程\\",\\"v\\":\\"3次卡\\"},{\\"k\\":\\"门店\\",\\"v\\":\\"通用\\"}]",
                   "price":"1811.00","payment":"3622.00","outer_sku_id":"%s"},
                  {"item_id":5505,"sku_id":101,"num":1,"item_title":"赠品超光子",
                   "sku_spec":"单次体验","discount_price":"199.95","payment":"0.00","outer_item_id":"GIFT-101"},
                  {"item_id":5505,"sku_id":202,"num":1,"goods_title":"未映射项目",
                   "sku_properties_name":"单次","price":"599.50","total_fee":"599.50"}
                ]}}
                """.formatted(longTitle, longOuterCode);
        YouzanEvent raw = new YouzanEvent(701L, SHOP, "ev-full-1", "ORDER_PAID", "ext-full-1",
                maskedPayload(), "cipher-full-1", true, "PULL", "CUSTOMER_UNRESOLVED", 1, "待链接客户", null,
                Instant.parse("2026-11-26T08:00:00Z"), null, null, providerMasked, "provider-cipher-secret", 0L);

        when(scopeSupport.scope(reader)).thenReturn(DataScope.all());
        when(repository.findEvent(TENANT_ID, 701L)).thenReturn(Optional.of(raw));
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-full-1")).thenReturn(Optional.empty());
        when(cipher.decrypt("cipher-full-1")).thenReturn(plainPayloadWithItems("ev-full-1", "ext-full-1",
                "{\"external_sku_id\":\"5505:101\",\"quantity\":2},"
                        + "{\"external_sku_id\":\"5505:101\",\"quantity\":1},"
                        + "{\"external_sku_id\":\"5505:202\",\"quantity\":1}"));
        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, "5505:101", 301L, "ACTIVE", Instant.now(), 0L),
                new YouzanSkuMapping(2L, SHOP, "5505:202", 302L, "INACTIVE", Instant.now(), 0L)
        ));
        when(catalogService.skusByIds(TENANT_ID, reader, Set.of(301L))).thenReturn(List.of(
                new Sku(301L, "SKU-WG-001-03", 301L, 201L, "光子水光囤货卡", 1, "FIXED", "3次",
                        181100L, "ACTIVE", null, null, null, 0L)
        ));

        YouzanOrderView view = service.order(TENANT_ID, reader, 701L);

        assertThat(view.payloadMaskedJson()).isNull();
        assertThat(view.items()).hasSize(3);

        YouzanOrderItemView first = view.items().get(0);
        assertThat(first.externalSkuId()).isEqualTo("5505:101");
        assertThat(first.quantity()).isEqualTo(2);
        assertThat(first.itemTitle()).hasSize(200).startsWith("超光子嫩肤套餐");
        assertThat(first.skuSpec()).isEqualTo("疗程：3次卡 / 门店：通用");
        assertThat(first.priceFen()).isEqualTo(181100L);
        assertThat(first.paymentFen()).isEqualTo(362200L);
        assertThat(first.outerSkuCode()).hasSize(128).startsWith("OUT-X");
        assertThat(first.crmSkuId()).isEqualTo(301L);
        assertThat(first.crmSkuName()).isEqualTo("光子水光囤货卡｜3次");

        // Second line shares external_sku_id but keeps its own line-level 0.00 payment and discount_price
        YouzanOrderItemView second = view.items().get(1);
        assertThat(second.externalSkuId()).isEqualTo("5505:101");
        assertThat(second.quantity()).isEqualTo(1);
        assertThat(second.itemTitle()).isEqualTo("赠品超光子");
        assertThat(second.skuSpec()).isEqualTo("单次体验");
        assertThat(second.priceFen()).isEqualTo(19995L);
        assertThat(second.paymentFen()).isZero();
        assertThat(second.outerSkuCode()).isEqualTo("GIFT-101");
        assertThat(second.crmSkuId()).isEqualTo(301L);
        assertThat(second.crmSkuName()).isEqualTo("光子水光囤货卡｜3次");

        // INACTIVE mapping is ignored; total_fee populates paymentFen
        YouzanOrderItemView third = view.items().get(2);
        assertThat(third.externalSkuId()).isEqualTo("5505:202");
        assertThat(third.quantity()).isEqualTo(1);
        assertThat(third.itemTitle()).isEqualTo("未映射项目");
        assertThat(third.skuSpec()).isEqualTo("单次");
        assertThat(third.priceFen()).isEqualTo(59950L);
        assertThat(third.paymentFen()).isEqualTo(59950L);
        assertThat(third.outerSkuCode()).isNull();
        assertThat(third.crmSkuId()).isNull();
        assertThat(third.crmSkuName()).isNull();

        String serialized = objectMapper.writeValueAsString(view);
        assertThat(serialized)
                .contains("\"external_sku_id\":\"5505:101\"", "\"paymentFen\":362200", "\"crmSkuName\":\"光子水光囤货卡｜3次\"")
                .doesNotContain("provider-cipher-secret", "cipher-full-1", "13900000000", "raw-nickname", "raw-address");
        verify(catalogService, times(1)).skusByIds(TENANT_ID, reader, Set.of(301L));
    }

    @Test
    void ordersPageBatchesSkuMappingsAndCatalogLookupsOnceAndFallsBackGracefully() {
        CurrentUser reader = user(5L, "order:read");
        String payloadFallbackJson = """
                {"items":[{"external_sku_id":"5505:101","quantity":1,"title":"回退自payloadMaskedJson",
                "sku_spec":"标准规格","price_fen":8800,"payment_fen":8000,"outer_sku_id":"PF-101"}]}
                """;
        String negativeMoneyProvider = """
                {"full_order_info":{"orders":[{"item_id":5505,"sku_id":102,"num":1,"title":"异常负价保护",
                "price_fen":-50,"payment":"-10.00"}]}}
                """;
        YouzanEvent raw1 = new YouzanEvent(801L, SHOP, "ev-batch-1", "ORDER_PAID", "ext-batch-1",
                payloadFallbackJson, "c-b1", true, "PULL", "CUSTOMER_UNRESOLVED", 1, null, null,
                Instant.parse("2026-11-26T09:00:00Z"), null, null, "{}", "pc-b1", 0L);
        YouzanEvent raw2 = new YouzanEvent(802L, SHOP, "ev-batch-2", "ORDER_PAID", "ext-batch-2",
                "{}", "c-b2", true, "PULL", "CUSTOMER_UNRESOLVED", 1, null, null,
                Instant.parse("2026-11-26T09:01:00Z"), null, null, negativeMoneyProvider, "pc-b2", 0L);
        YouzanEvent raw3Mapped = new YouzanEvent(803L, SHOP, "ev-batch-3", "ORDER_PAID", "ext-batch-3",
                "{}", "c-b3", true, "PULL", "PROCESSED", 1, null, null,
                Instant.parse("2026-11-26T09:02:00Z"), null, 901L, null, null, 0L);

        when(scopeSupport.scope(reader)).thenReturn(DataScope.all());
        when(repository.findLatestOrderEvents(eq(TENANT_ID), eq(SHOP), any(), eq(0), eq(20)))
                .thenReturn(List.of(raw1, raw2, raw3Mapped));
        when(repository.countDistinctExternalOrders(eq(TENANT_ID), eq(SHOP), any())).thenReturn(3L);
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-batch-1")).thenReturn(Optional.empty());
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-batch-2")).thenReturn(Optional.empty());
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-batch-3"))
                .thenReturn(Optional.of(new YouzanOrderMap(99L, SHOP, "ext-batch-3", 901L, Instant.now(), null, 0L)));
        when(cipher.decrypt("c-b1")).thenReturn(plainPayloadWithSkus("ev-batch-1", "ext-batch-1", List.of("5505:101")));
        when(cipher.decrypt("c-b2")).thenReturn(plainPayloadWithSkus("ev-batch-2", "ext-batch-2", List.of("5505:102")));
        when(cipher.decrypt("c-b3")).thenReturn(plainPayloadWithSkus("ev-batch-3", "ext-batch-3", List.of("5505:OLD")));

        OrderFulfillmentView fulfillment = mock(OrderFulfillmentView.class);
        OrderDetail orderDetail = mock(OrderDetail.class);
        Order order = mock(Order.class);
        when(fulfillment.order()).thenReturn(orderDetail);
        when(orderDetail.order()).thenReturn(order);
        when(orderDetail.payments()).thenReturn(List.of());
        when(orderDetail.items()).thenReturn(List.of(
                new OrderItem(1L, 901L, 399L, 399L, "SKU-HIST", "历史冻结套餐｜1次", 2, 181100L, 181100L, "{}", 0L)
        ));
        when(order.status()).thenReturn("PAID");
        when(order.paidFen()).thenReturn(181100L);
        when(order.sourceSystem()).thenReturn("YOUZAN");
        when(order.externalShopId()).thenReturn(SHOP);
        when(orderService.detail(TENANT_ID, reader, 901L)).thenReturn(fulfillment);

        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, "5505:101", 301L, "ACTIVE", Instant.now(), 0L),
                new YouzanSkuMapping(2L, SHOP, "5505:102", 302L, "ACTIVE", Instant.now(), 0L)
        ));
        when(catalogService.skusByIds(TENANT_ID, reader, Set.of(301L, 302L))).thenReturn(List.of(
                new Sku(301L, "SKU-301", 301L, 201L, "光子水光囤货卡", 1, "FIXED", "3次", 181100L, "ACTIVE", null, null, null, 0L),
                new Sku(302L, "SKU-302", 302L, 201L, "光子水光囤货卡", 2, "FIXED", null, 331100L, "ACTIVE", null, null, null, 0L)
        ));

        PageResponse<YouzanOrderView> page = service.orders(TENANT_ID, reader, SHOP, 1, 20);

        assertThat(page.items()).hasSize(3);
        // raw1 fell back to payloadMaskedJson because providerPayloadMaskedJson had no order lines
        YouzanOrderItemView item1 = page.items().get(0).items().get(0);
        assertThat(item1.itemTitle()).isEqualTo("回退自payloadMaskedJson");
        assertThat(item1.priceFen()).isEqualTo(8800L);
        assertThat(item1.paymentFen()).isEqualTo(8000L);
        assertThat(item1.outerSkuCode()).isEqualTo("PF-101");
        assertThat(item1.crmSkuId()).isEqualTo(301L);
        assertThat(item1.crmSkuName()).isEqualTo("光子水光囤货卡｜3次");

        // raw2 rejected negative prices/payments safely as null while enriching SKU name
        YouzanOrderItemView item2 = page.items().get(1).items().get(0);
        assertThat(item2.itemTitle()).isEqualTo("异常负价保护");
        assertThat(item2.priceFen()).isNull();
        assertThat(item2.paymentFen()).isNull();
        assertThat(item2.crmSkuId()).isEqualTo(302L);
        assertThat(item2.crmSkuName()).isEqualTo("光子水光囤货卡");

        // raw3 preserved historical frozen OrderItem snapshot even without active SKU mapping and did NOT query catalog for 399
        YouzanOrderItemView item3 = page.items().get(2).items().get(0);
        assertThat(item3.externalSkuId()).isEqualTo("5505:OLD");
        assertThat(item3.itemTitle()).isNull();
        assertThat(item3.crmSkuId()).isEqualTo(399L);
        assertThat(item3.crmSkuName()).isEqualTo("历史冻结套餐｜1次");

        // Verify page-level batching: 1 findSkuMappings + 1 skusByIds for the whole page (no N+1)
        verify(repository, times(1)).findSkuMappings(TENANT_ID, SHOP);
        verify(catalogService, times(1)).skusByIds(TENANT_ID, reader, Set.of(301L, 302L));
    }

    @Test
    void catalogLookupFailureDegradesGracefullyWithoutThrowing403Or500() {
        CurrentUser readerWithoutCatalog = user(6L, "order:read");
        YouzanEvent raw = new YouzanEvent(810L, SHOP, "ev-nocat-1", "ORDER_PAID", "ext-nocat-1",
                "{}", "c-nocat", true, "PULL", "CUSTOMER_UNRESOLVED", 1, null, null,
                Instant.parse("2026-11-26T10:00:00Z"), null, null,
                "{\"full_order_info\":{\"orders\":[{\"item_id\":5505,\"sku_id\":101,\"num\":1,\"title\":\"水光体验\",\"price\":\"599.00\"}]}}",
                "pc-nocat", 0L);

        when(scopeSupport.scope(readerWithoutCatalog)).thenReturn(DataScope.all());
        when(repository.findEvent(TENANT_ID, 810L)).thenReturn(Optional.of(raw));
        when(repository.findOrderMap(TENANT_ID, SHOP, "ext-nocat-1")).thenReturn(Optional.empty());
        when(cipher.decrypt("c-nocat")).thenReturn(plainPayloadWithSkus("ev-nocat-1", "ext-nocat-1", List.of("5505:101")));
        when(repository.findSkuMappings(TENANT_ID, SHOP)).thenReturn(List.of(
                new YouzanSkuMapping(1L, SHOP, "5505:101", 301L, "ACTIVE", Instant.now(), 0L)
        ));
        when(catalogService.skusByIds(TENANT_ID, readerWithoutCatalog, Set.of(301L)))
                .thenThrow(new AccessDeniedException("no product:read"));

        YouzanOrderView view = service.order(TENANT_ID, readerWithoutCatalog, 810L);

        assertThat(view.payloadMaskedJson()).isNull();
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).itemTitle()).isEqualTo("水光体验");
        assertThat(view.items().get(0).priceFen()).isEqualTo(59900L);
        assertThat(view.items().get(0).crmSkuId()).isEqualTo(301L);
        assertThat(view.items().get(0).crmSkuName()).isNull();
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
        return plainPayloadWithSkus(eventId, externalOrderNo, List.of("301:9"));
    }

    private String plainPayloadWithSkus(String eventId, String externalOrderNo, List<String> skuIds) {
        String itemsJson = skuIds.stream()
                .map(skuId -> "{\"external_sku_id\":\"" + skuId + "\",\"quantity\":2}")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return plainPayloadWithItems(eventId, externalOrderNo, itemsJson);
    }

    private String plainPayloadWithItems(String eventId, String externalOrderNo, String itemsJson) {
        return """
                {"event_id":"%s","event_type":"ORDER_PAID","shop_id":"%s",
                "external_order_no":"%s","external_customer_id":"external-customer-1",
                "amount_fen":181100,"external_updated_at":"2026-10-24T04:00:00Z",
                "order_type_code":"REGULAR","items":[%s],
                "mobile":"13900000000","nickname":"raw-nickname",
                "receiver":{"address":"raw-address"}}
                """.formatted(eventId, SHOP, externalOrderNo, itemsJson);
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
