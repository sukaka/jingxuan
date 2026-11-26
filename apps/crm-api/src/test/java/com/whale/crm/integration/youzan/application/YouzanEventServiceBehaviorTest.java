package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanSkuMapping;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.application.PaymentApplicationService;
import com.whale.crm.trading.domain.Order;
import com.whale.crm.trading.domain.OrderDetail;
import com.whale.crm.trading.domain.OrderFulfillmentView;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanEventServiceBehaviorTest {
    private static final long TENANT_ID = 1L;
    private static final String SHOP = "SHOP-BIZ-06";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sameSecondOrderPaidOnDraftOrderLocksCustomerBeforeOrderAndConfirmsPayment() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        AuditService auditService = mock(AuditService.class);
        OutboxService outboxService = mock(OutboxService.class);

        Instant sameSecond = Instant.parse("2026-10-24T12:00:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent paidEvent = new YouzanOrderEvent("EV-PAID-SAME-SEC", "ORDER_PAID", SHOP, "EXT-ORD-1",
                "EXT-CUST-1", "TR-1", "PAID", 181100L, sameSecond, sameSecond, "REGULAR", null, null,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        String paidJson = mapper.writeValueAsString(paidEvent);
        YouzanEvent raw = new YouzanEvent(201L, SHOP, "EV-PAID-SAME-SEC", "ORDER_PAID", "EXT-ORD-1",
                "{}", "cipher-paid", true, "PULL", "RECEIVED", 0, null, null, sameSecond, null, null, 0L);
        YouzanEvent done = new YouzanEvent(201L, SHOP, "EV-PAID-SAME-SEC", "ORDER_PAID", "EXT-ORD-1",
                "{}", "cipher-paid", true, "PULL", "PROCESSED", 1, null, null, sameSecond, sameSecond, 901L, 1L);
        YouzanOrderMap orderMap = new YouzanOrderMap(51L, SHOP, "EXT-ORD-1", 901L, sameSecond, sameSecond, 0L);

        Order draftOrder = order(901L, 1010L, "DRAFT", 181100L, 0L, 0L);
        Order pendingOrder = order(901L, 1010L, "PENDING_PAYMENT", 181100L, 0L, 1L);
        Order paidOrder = order(901L, 1010L, "PAID", 181100L, 181100L, 2L);
        OrderFulfillmentView draftView = new OrderFulfillmentView(new OrderDetail(draftOrder, List.of(), List.of(), List.of()), List.of());
        OrderFulfillmentView pendingView = new OrderFulfillmentView(new OrderDetail(pendingOrder, List.of(), List.of(), List.of()), List.of());
        OrderFulfillmentView paidView = new OrderFulfillmentView(new OrderDetail(paidOrder, List.of(), List.of(), List.of()), List.of());

        when(repository.findEventForUpdate(TENANT_ID, 201L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-paid")).thenReturn(paidJson);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-ORD-1")).thenReturn(Optional.of(orderMap));
        when(repository.findOrderMapForUpdate(TENANT_ID, SHOP, "EXT-ORD-1")).thenReturn(Optional.of(orderMap));
        when(orderService.detail(TENANT_ID, actor, 901L)).thenReturn(draftView, draftView);
        when(orderService.submit(eq(TENANT_ID), eq(actor), eq(901L), any())).thenReturn(pendingView);
        when(paymentService.confirmProviderCallback(eq(TENANT_ID), eq(actor), eq(901L), any(), eq(SHOP + ":TR-1")))
                .thenReturn(paidView);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(201L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(901L)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 201L)).thenReturn(Optional.of(done));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, auditService, outboxService, txManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = service.process(TENANT_ID, 201L);

        assertThat(response.processStatus()).isEqualTo("PROCESSED");
        assertThat(response.duplicate()).isFalse();
        InOrder ordered = inOrder(scopeSupport, orderService, paymentService);
        ordered.verify(scopeSupport).requireVisibleForUpdate(TENANT_ID, 1010L, actor);
        ordered.verify(orderService).submit(eq(TENANT_ID), eq(actor), eq(901L), any());
        ordered.verify(paymentService).confirmProviderCallback(eq(TENANT_ID), eq(actor), eq(901L), any(), eq(SHOP + ":TR-1"));
    }

    @Test
    void sameSecondDuplicatePaidOrWaitPayAfterPaidIsCompletedAsDuplicateWithoutReconfirming() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);

        Instant sameSecond = Instant.parse("2026-10-24T12:00:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent paidDup = new YouzanOrderEvent("EV-PAID-DUP", "ORDER_PAID", SHOP, "EXT-ORD-2",
                "EXT-CUST-1", "TR-2", "PAID", 181100L, sameSecond, sameSecond, "REGULAR", null, null,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        String json = mapper.writeValueAsString(paidDup);
        YouzanEvent raw = new YouzanEvent(202L, SHOP, "EV-PAID-DUP", "ORDER_PAID", "EXT-ORD-2",
                "{}", "cipher-dup", true, "PULL", "RECEIVED", 0, null, null, sameSecond, null, null, 0L);
        YouzanEvent done = new YouzanEvent(202L, SHOP, "EV-PAID-DUP", "ORDER_PAID", "EXT-ORD-2",
                "{}", "cipher-dup", true, "PULL", "PROCESSED", 1, null, null, sameSecond, sameSecond, 902L, 1L);
        YouzanOrderMap orderMap = new YouzanOrderMap(52L, SHOP, "EXT-ORD-2", 902L, sameSecond, sameSecond, 0L);
        Order paidOrder = order(902L, 1010L, "PAID", 181100L, 181100L, 2L);
        OrderFulfillmentView paidView = new OrderFulfillmentView(new OrderDetail(paidOrder, List.of(), List.of(), List.of()), List.of());

        when(repository.findEventForUpdate(TENANT_ID, 202L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-dup")).thenReturn(json);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-ORD-2")).thenReturn(Optional.of(orderMap));
        when(repository.findOrderMapForUpdate(TENANT_ID, SHOP, "EXT-ORD-2")).thenReturn(Optional.of(orderMap));
        when(orderService.detail(TENANT_ID, actor, 902L)).thenReturn(paidView);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(202L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(902L)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 202L)).thenReturn(Optional.of(done));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, mock(AuditService.class), mock(OutboxService.class),
                txManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = service.process(TENANT_ID, 202L);
        assertThat(response.duplicate()).isTrue();
        verify(scopeSupport, never()).requireVisibleForUpdate(anyLong(), anyLong(), any());
        verify(orderService, never()).submit(anyLong(), any(), anyLong(), any());
        verify(paymentService, never()).confirmProviderCallback(anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    void newExternalOrderAcquiresCustomerRowLockBeforeCreateOrder() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);

        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent waitEvent = new YouzanOrderEvent("EV-NEW-1", "WAIT_PAY", SHOP, "EXT-NEW-1",
                "EXT-CUST-1", null, "WAIT_PAY", 181100L, null, now, "REGULAR", null, null,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        String json = mapper.writeValueAsString(waitEvent);
        YouzanEvent raw = new YouzanEvent(203L, SHOP, "EV-NEW-1", "WAIT_PAY", "EXT-NEW-1",
                "{}", "cipher-new", true, "PULL", "RECEIVED", 0, null, null, now, null, null, 0L);
        YouzanEvent done = new YouzanEvent(203L, SHOP, "EV-NEW-1", "WAIT_PAY", "EXT-NEW-1",
                "{}", "cipher-new", true, "PULL", "PROCESSED", 1, null, null, now, now, 903L, 1L);
        YouzanOrderMap createdMap = new YouzanOrderMap(53L, SHOP, "EXT-NEW-1", 903L, now, now, 0L);

        when(repository.findEventForUpdate(TENANT_ID, 203L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-new")).thenReturn(json);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-NEW-1")).thenReturn(Optional.empty(), Optional.of(createdMap));
        when(repository.findOrderMapForUpdate(TENANT_ID, SHOP, "EXT-NEW-1")).thenReturn(Optional.of(createdMap));
        when(repository.findSkuMapping(TENANT_ID, SHOP, "YZ-SKU-1"))
                .thenReturn(Optional.of(new YouzanSkuMapping(1L, SHOP, "YZ-SKU-1", 301L, "ACTIVE", now, 0L)));
        when(repository.findCustomerLink(TENANT_ID, SHOP, "EXT-CUST-1"))
                .thenReturn(Optional.of(new YouzanCustomerLink(2L, SHOP, "EXT-CUST-1", 1010L, "MANUAL", "HIGH", "ok", 1L, now, 0L)));
        when(scopeSupport.requireVisibleForUpdate(TENANT_ID, 1010L, actor)).thenReturn(new Customer());
        Order createdOrder = order(903L, 1010L, "DRAFT", 181100L, 0L, 0L);
        OrderFulfillmentView createdView = new OrderFulfillmentView(new OrderDetail(createdOrder, List.of(), List.of(), List.of()), List.of());
        when(orderService.createExternalOrder(eq(TENANT_ID), eq(actor), any(), eq("YOUZAN"), eq(SHOP), eq("EXT-NEW-1")))
                .thenReturn(createdView);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(203L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(903L)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 203L)).thenReturn(Optional.of(done));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, mock(AuditService.class), mock(OutboxService.class),
                txManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = service.process(TENANT_ID, 203L);
        assertThat(response.processStatus()).isEqualTo("PROCESSED");
        InOrder ordered = inOrder(scopeSupport, orderService);
        ordered.verify(scopeSupport).requireVisibleForUpdate(TENANT_ID, 1010L, actor);
        ordered.verify(orderService).createExternalOrder(eq(TENANT_ID), eq(actor), any(), eq("YOUZAN"), eq(SHOP), eq("EXT-NEW-1"));
    }

    @Test
    void orderClosedWithoutExistingOrderCompletesCleanlyWithoutOrderOrIssues() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        AuditService auditService = mock(AuditService.class);
        OutboxService outboxService = mock(OutboxService.class);

        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent closedEvent = new YouzanOrderEvent("EV-CLOSED-1", "ORDER_CLOSED", SHOP, "EXT-CLOSED-1",
                "UNMAPPED-CUST", null, "TRADE_CLOSED_BY_USER", 181100L, null, now, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("UNMAPPED-SKU", 1)));
        String json = mapper.writeValueAsString(closedEvent);
        YouzanEvent raw = new YouzanEvent(204L, SHOP, "EV-CLOSED-1", "ORDER_CLOSED", "EXT-CLOSED-1",
                "{}", "cipher-closed", true, "PULL", "RECEIVED", 0, null, null, now, null, null, 0L);
        YouzanEvent done = new YouzanEvent(204L, SHOP, "EV-CLOSED-1", "ORDER_CLOSED", "EXT-CLOSED-1",
                "{}", "cipher-closed", true, "PULL", "PROCESSED", 1, null, null, now, now, null, 1L);

        when(repository.findEventForUpdate(TENANT_ID, 204L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-closed")).thenReturn(json);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-CLOSED-1")).thenReturn(Optional.empty());
        when(repository.updateEventStatus(eq(TENANT_ID), eq(204L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(null)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 204L)).thenReturn(Optional.of(done));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, auditService, outboxService, txManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = service.process(TENANT_ID, 204L);

        assertThat(response.processStatus()).isEqualTo("PROCESSED");
        assertThat(response.orderId()).isNull();
        verify(orderService, never()).createExternalOrder(anyLong(), any(), any(), any(), any(), any());
        verify(paymentService, never()).confirmProviderCallback(anyLong(), any(), anyLong(), any(), any());
        verify(repository, never()).insertIssue(anyLong(), any());
        verify(auditService).record(eq(TENANT_ID), eq(1L), eq("YOUZAN_ORDER_CLOSED_IGNORED"), eq("YOUZAN"),
                eq("EXT-CLOSED-1"), any(), any(), any());
    }

    @Test
    void orderClosedWithoutOrderResolvesHistoricalUnpaidOpenIssuesAndSkipsWhenExternallyPaid() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        AuditService auditService = mock(AuditService.class);
        OutboxService outboxService = mock(OutboxService.class);

        Instant now = Instant.parse("2026-10-24T12:10:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent closedUnpaid = new YouzanOrderEvent("EV-CLOSED-HIST", "ORDER_CLOSED", SHOP, "EXT-HIST-1",
                "UNMAPPED-CUST", null, "TRADE_CLOSED_BY_USER", 181100L, null, now, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("UNMAPPED-SKU", 1)));
        YouzanEvent rawUnpaid = new YouzanEvent(207L, SHOP, "EV-CLOSED-HIST", "ORDER_CLOSED", "EXT-HIST-1",
                "{}", "cipher-closed-hist", true, "PULL", "RECEIVED", 0, null, null, now, null, null, 0L);
        YouzanEvent doneUnpaid = new YouzanEvent(207L, SHOP, "EV-CLOSED-HIST", "ORDER_CLOSED", "EXT-HIST-1",
                "{}", "cipher-closed-hist", true, "PULL", "PROCESSED", 1, null, null, now, now, null, 1L);
        YouzanSyncIssue historicalIssue = new YouzanSyncIssue(881L, 199L, "MAPPING_FAILED", "OPEN",
                "外部SKU未映射: UNMAPPED-SKU", 1, "外部SKU未映射: UNMAPPED-SKU", null, now.minusSeconds(300),
                null, null, 2L);

        when(repository.findEventForUpdate(TENANT_ID, 207L)).thenReturn(Optional.of(rawUnpaid));
        when(cipher.decrypt("cipher-closed-hist")).thenReturn(mapper.writeValueAsString(closedUnpaid));
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-HIST-1")).thenReturn(Optional.empty());
        when(repository.updateEventStatus(eq(TENANT_ID), eq(207L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(null)))
                .thenReturn(1);
        when(repository.findOpenUnpaidIssuesByExternalOrderForUpdate(TENANT_ID, SHOP, "EXT-HIST-1", 207L))
                .thenReturn(List.of(historicalIssue));
        when(repository.updateIssueStatus(eq(TENANT_ID), eq(881L), eq(2L), eq("RESOLVED"), eq(1),
                eq("外部SKU未映射: UNMAPPED-SKU"), eq(null), any(Instant.class), eq(1L)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 207L)).thenReturn(Optional.of(doneUnpaid));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, auditService, outboxService, txManager(), mock(TaskExecutor.class));

        YouzanEventResponse unpaidResponse = service.process(TENANT_ID, 207L);
        assertThat(unpaidResponse.processStatus()).isEqualTo("PROCESSED");
        verify(repository).updateIssueStatus(eq(TENANT_ID), eq(881L), eq(2L), eq("RESOLVED"), eq(1),
                eq("外部SKU未映射: UNMAPPED-SKU"), eq(null), any(Instant.class), eq(1L));
        verify(auditService).record(eq(TENANT_ID), eq(1L), eq("YOUZAN_ORDER_CLOSED_IGNORED"), eq("YOUZAN"),
                eq("EXT-HIST-1"), eq(null),
                argThat(after -> after instanceof Map<?, ?> map
                        && List.of(881L).equals(map.get("resolvedHistoricalIssueIds"))),
                eq(null));

        YouzanOrderEvent closedAfterExternalPay = new YouzanOrderEvent("EV-CLOSED-PAID-EXT", "ORDER_CLOSED", SHOP,
                "EXT-HIST-2", "UNMAPPED-CUST", "TR-EXT-2", "TRADE_CLOSED", 181100L, now.minusSeconds(60), now,
                "REGULAR", null, null, "PLATFORM", null, List.of(new YouzanOrderLine("UNMAPPED-SKU", 1)));
        YouzanEvent rawPaidExt = new YouzanEvent(208L, SHOP, "EV-CLOSED-PAID-EXT", "ORDER_CLOSED", "EXT-HIST-2",
                "{}", "cipher-closed-paid-ext", true, "PULL", "RECEIVED", 0, null, null, now, null, null, 0L);
        YouzanEvent donePaidExt = new YouzanEvent(208L, SHOP, "EV-CLOSED-PAID-EXT", "ORDER_CLOSED", "EXT-HIST-2",
                "{}", "cipher-closed-paid-ext", true, "PULL", "PROCESSED", 1, null, null, now, now, null, 1L);
        when(repository.findEventForUpdate(TENANT_ID, 208L)).thenReturn(Optional.of(rawPaidExt));
        when(cipher.decrypt("cipher-closed-paid-ext")).thenReturn(mapper.writeValueAsString(closedAfterExternalPay));
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-HIST-2")).thenReturn(Optional.empty());
        when(repository.updateEventStatus(eq(TENANT_ID), eq(208L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(null)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 208L)).thenReturn(Optional.of(donePaidExt));

        YouzanEventResponse paidExtResponse = service.process(TENANT_ID, 208L);
        assertThat(paidExtResponse.processStatus()).isEqualTo("PROCESSED");
        verify(repository, never()).findOpenUnpaidIssuesByExternalOrderForUpdate(TENANT_ID, SHOP, "EXT-HIST-2", 208L);
        verify(repository, never()).updateIssueStatus(eq(TENANT_ID), eq(882L), anyLong(), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void orderClosedCancelsUnpaidOrderAndFlagsPaidOrderAsPaymentMismatchWithoutMutatingPaidFacts() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);

        Instant t0 = Instant.parse("2026-10-24T12:00:00Z");
        Instant t1 = Instant.parse("2026-10-24T12:05:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent closeDraft = new YouzanOrderEvent("EV-CLOSE-DRAFT", "ORDER_CLOSED", SHOP, "EXT-DRAFT-1",
                "EXT-CUST-1", null, "TRADE_CLOSED_BY_USER", 181100L, null, t1, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        YouzanEvent rawDraft = new YouzanEvent(205L, SHOP, "EV-CLOSE-DRAFT", "ORDER_CLOSED", "EXT-DRAFT-1",
                "{}", "cipher-close-draft", true, "PULL", "RECEIVED", 0, null, null, t1, null, null, 0L);
        YouzanEvent doneDraft = new YouzanEvent(205L, SHOP, "EV-CLOSE-DRAFT", "ORDER_CLOSED", "EXT-DRAFT-1",
                "{}", "cipher-close-draft", true, "PULL", "PROCESSED", 1, null, null, t1, t1, 905L, 1L);
        YouzanOrderMap draftMap = new YouzanOrderMap(55L, SHOP, "EXT-DRAFT-1", 905L, t0, t0, 0L);
        Order draftOrder = order(905L, 1010L, "DRAFT", 181100L, 0L, 0L);
        OrderFulfillmentView draftView = new OrderFulfillmentView(new OrderDetail(draftOrder, List.of(), List.of(), List.of()), List.of());

        when(repository.findEventForUpdate(TENANT_ID, 205L)).thenReturn(Optional.of(rawDraft));
        when(cipher.decrypt("cipher-close-draft")).thenReturn(mapper.writeValueAsString(closeDraft));
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-DRAFT-1")).thenReturn(Optional.of(draftMap));
        when(repository.findOrderMapForUpdate(TENANT_ID, SHOP, "EXT-DRAFT-1")).thenReturn(Optional.of(draftMap));
        when(orderService.detail(TENANT_ID, actor, 905L)).thenReturn(draftView, draftView);
        when(repository.updateOrderMapTime(TENANT_ID, 55L, 0L, t1)).thenReturn(1);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(205L), eq(0L), eq("PROCESSED"), any(), any(), any(), eq(905L)))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 205L)).thenReturn(Optional.of(doneDraft));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, mock(AuditService.class), mock(OutboxService.class),
                txManager(), mock(TaskExecutor.class));

        YouzanEventResponse draftResponse = service.process(TENANT_ID, 205L);
        assertThat(draftResponse.processStatus()).isEqualTo("PROCESSED");
        InOrder ordered = inOrder(scopeSupport, orderService);
        ordered.verify(scopeSupport).requireVisibleForUpdate(TENANT_ID, 1010L, actor);
        ordered.verify(orderService).cancel(eq(TENANT_ID), eq(actor), eq(905L), any());

        YouzanOrderEvent closePaid = new YouzanOrderEvent("EV-CLOSE-PAID", "ORDER_CLOSED", SHOP, "EXT-PAID-1",
                "EXT-CUST-1", null, "TRADE_CLOSED", 181100L, null, t1, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        YouzanEvent rawPaid = new YouzanEvent(206L, SHOP, "EV-CLOSE-PAID", "ORDER_CLOSED", "EXT-PAID-1",
                "{}", "cipher-close-paid", true, "PULL", "RECEIVED", 0, null, null, t1, null, null, 0L);
        YouzanEvent mismatchPaid = new YouzanEvent(206L, SHOP, "EV-CLOSE-PAID", "ORDER_CLOSED", "EXT-PAID-1",
                "{}", "cipher-close-paid", true, "PULL", "PAYMENT_MISMATCH", 1, "有赞订单已关闭，但CRM订单已支付，请核实是否需在CRM人工退款",
                null, t1, t1, null, 1L);
        YouzanOrderMap paidMap = new YouzanOrderMap(56L, SHOP, "EXT-PAID-1", 906L, t0, t0, 0L);
        Order paidOrder = order(906L, 1010L, "PAID", 181100L, 181100L, 2L);
        OrderFulfillmentView paidView = new OrderFulfillmentView(new OrderDetail(paidOrder, List.of(), List.of(), List.of()), List.of());

        when(repository.findEventForUpdate(TENANT_ID, 206L)).thenReturn(Optional.of(rawPaid));
        when(cipher.decrypt("cipher-close-paid")).thenReturn(mapper.writeValueAsString(closePaid));
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-PAID-1")).thenReturn(Optional.of(paidMap));
        when(orderService.detail(TENANT_ID, actor, 906L)).thenReturn(paidView, paidView);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(206L), eq(0L), eq("PAYMENT_MISMATCH"), any(), any(), any(), any()))
                .thenReturn(1);
        when(repository.findEvent(TENANT_ID, 206L)).thenReturn(Optional.of(mismatchPaid));

        YouzanEventResponse paidResponse = service.process(TENANT_ID, 206L);
        assertThat(paidResponse.processStatus()).isEqualTo("PAYMENT_MISMATCH");
        assertThat(paidResponse.lastError()).contains("人工退款");
        verify(orderService, never()).cancel(eq(TENANT_ID), eq(actor), eq(906L), any());
        verify(repository).insertIssue(eq(TENANT_ID), any());
    }

    @Test
    void completeLinksOrderAndCustomerToYouzanSyncIssuesWhenIssuesExist() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        OrderApplicationService orderService = mock(OrderApplicationService.class);
        PaymentApplicationService paymentService = mock(PaymentApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        YouzanRiskLinkOperations riskLinkOperations = mock(YouzanRiskLinkOperations.class);

        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent paidEvent = new YouzanOrderEvent("EV-LINK-ORD", "ORDER_PAID", SHOP, "EXT-LINK-ORD",
                "EXT-CUST-1", "TR-LINK-1", "PAID", 181100L, now, now, "REGULAR", null, null,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine("YZ-SKU-1", 1)));
        String json = mapper.writeValueAsString(paidEvent);
        YouzanEvent raw = new YouzanEvent(209L, SHOP, "EV-LINK-ORD", "ORDER_PAID", "EXT-LINK-ORD",
                "{}", "cipher-link", true, "PULL", "RECEIVED", 0, null, null, now, null, null, 0L);
        YouzanEvent done = new YouzanEvent(209L, SHOP, "EV-LINK-ORD", "ORDER_PAID", "EXT-LINK-ORD",
                "{}", "cipher-link", true, "PULL", "PROCESSED", 1, null, null, now, now, 909L, 1L);
        YouzanOrderMap orderMap = new YouzanOrderMap(59L, SHOP, "EXT-LINK-ORD", 909L, now, now, 0L);
        Order paidOrder = order(909L, 1010L, "PAID", 181100L, 181100L, 2L);
        OrderFulfillmentView paidView = new OrderFulfillmentView(
                new OrderDetail(paidOrder, List.of(), List.of(), List.of()), List.of());

        when(repository.findEventForUpdate(TENANT_ID, 209L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-link")).thenReturn(json);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-LINK-ORD")).thenReturn(Optional.of(orderMap));
        when(repository.findOrderMapForUpdate(TENANT_ID, SHOP, "EXT-LINK-ORD")).thenReturn(Optional.of(orderMap));
        when(orderService.detail(TENANT_ID, actor, 909L)).thenReturn(paidView);
        when(repository.updateEventStatus(eq(TENANT_ID), eq(209L), eq(0L), eq("PROCESSED"),
                any(), any(), any(), eq(909L))).thenReturn(1);
        when(repository.findIssueIdsByEventOrOrder(TENANT_ID, SHOP, "EXT-LINK-ORD", 209L))
                .thenReturn(List.of(801L, 802L));
        when(repository.findEvent(TENANT_ID, 209L)).thenReturn(Optional.of(done));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher, orderService, paymentService,
                scopeSupport, currentUserService, mock(AuditService.class), mock(OutboxService.class),
                txManager(), mock(TaskExecutor.class), riskLinkOperations);

        YouzanEventResponse response = service.process(TENANT_ID, 209L);
        assertThat(response.processStatus()).isEqualTo("PROCESSED");
        verify(riskLinkOperations).linkOrderToYouzanIssues(TENANT_ID, List.of("801", "802"), 909L, 1010L);
    }

    @Test
    void replacePayloadAndProcessPassesExpectedEventIdCasGuardWhenExternalOrderNoChanges() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), txManager(), mock(TaskExecutor.class), YouzanRiskLinkOperations.NOOP);

        Instant now = Instant.parse("2026-10-24T12:00:00Z");

        // 1. Old order still has prior event 605 -> setOrderLatest receives expectedEventId=610L CAS guard
        YouzanEvent rawWithRemaining = new YouzanEvent(610L, SHOP, "EV-CAS-1", "ORDER_PAID", "ORD-OLD-1",
                null, null, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "RECEIVED", 0, null, null,
                now, null, null, "{}", "pc", 2L);
        YouzanEvent doneWithRemaining = new YouzanEvent(610L, SHOP, "EV-CAS-1", "ORDER_PAID", "ORD-NEW-1",
                "yz-cust-1", null, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "PROCESSED", 1, null, null,
                now, now, 910L, "{}", "pc", 3L);
        when(repository.findEventForUpdate(TENANT_ID, 610L))
                .thenReturn(Optional.of(rawWithRemaining), Optional.of(doneWithRemaining));
        when(repository.updateEventPayload(eq(TENANT_ID), eq(610L), eq(2L), eq("ORD-NEW-1"), eq("yz-cust-1"),
                any(), eq("ORDER_PAID"), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.findMaxEventIdByExternalOrder(TENANT_ID, SHOP, "ORD-OLD-1")).thenReturn(605L);
        when(repository.setOrderLatest(TENANT_ID, SHOP, "ORD-OLD-1", 605L, 610L)).thenReturn(1);

        YouzanOrderEvent detail1 = new YouzanOrderEvent("EV-CAS-1", "ORDER_PAID", SHOP, "ORD-NEW-1",
                "yz-cust-1", "TR-CAS-1", "PAID", 1000L, now, now, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("SKU-1", 1)));
        service.replacePayloadAndProcess(TENANT_ID, 610L, detail1, "{}");

        verify(repository).upsertOrderLatest(TENANT_ID, SHOP, "ORD-NEW-1", 610L);
        verify(repository).setOrderLatest(TENANT_ID, SHOP, "ORD-OLD-1", 605L, 610L);
        verify(repository, never()).deleteOrderLatest(eq(TENANT_ID), eq(SHOP), eq("ORD-OLD-1"), anyLong());

        // 2. Old order has no remaining events -> deleteOrderLatest receives expectedEventId=611L CAS guard
        YouzanEvent rawNoRemaining = new YouzanEvent(611L, SHOP, "EV-CAS-2", "ORDER_PAID", "ORD-OLD-2",
                null, null, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "RECEIVED", 0, null, null,
                now, null, null, "{}", "pc", 2L);
        YouzanEvent doneNoRemaining = new YouzanEvent(611L, SHOP, "EV-CAS-2", "ORDER_PAID", "ORD-NEW-2",
                "yz-cust-2", null, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "PROCESSED", 1, null, null,
                now, now, 911L, "{}", "pc", 3L);
        when(repository.findEventForUpdate(TENANT_ID, 611L))
                .thenReturn(Optional.of(rawNoRemaining), Optional.of(doneNoRemaining));
        when(repository.updateEventPayload(eq(TENANT_ID), eq(611L), eq(2L), eq("ORD-NEW-2"), eq("yz-cust-2"),
                any(), eq("ORDER_PAID"), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.findMaxEventIdByExternalOrder(TENANT_ID, SHOP, "ORD-OLD-2")).thenReturn(null);
        when(repository.deleteOrderLatest(TENANT_ID, SHOP, "ORD-OLD-2", 611L)).thenReturn(1);

        YouzanOrderEvent detail2 = new YouzanOrderEvent("EV-CAS-2", "ORDER_PAID", SHOP, "ORD-NEW-2",
                "yz-cust-2", "TR-CAS-2", "PAID", 1000L, now, now, "REGULAR", null, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("SKU-1", 1)));
        service.replacePayloadAndProcess(TENANT_ID, 611L, detail2, "{}");

        verify(repository).upsertOrderLatest(TENANT_ID, SHOP, "ORD-NEW-2", 611L);
        verify(repository).deleteOrderLatest(TENANT_ID, SHOP, "ORD-OLD-2", 611L);
        verify(repository, never()).setOrderLatest(eq(TENANT_ID), eq(SHOP), eq("ORD-OLD-2"), anyLong(), anyLong());
    }

    @Test
    void markFailureResolvesSupersededRetryingMappingIssueWhenAdvancingToCustomerUnresolved() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        Instant now = Instant.parse("2026-11-26T12:00:00Z");

        CurrentUser actor = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("order:write"), Set.of());
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.of(actor));

        YouzanOrderEvent ev = new YouzanOrderEvent("EV-ADV-1", "ORDER_PAID", SHOP, "EXT-ADV-1",
                "EXT-CUST-UNLINKED", "TR-ADV-1", "PAID", 181100L, now, now, "REGULAR", null, null,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine("5505:100", 1)));
        String json = mapper.writeValueAsString(ev);
        YouzanEvent raw = new YouzanEvent(210L, SHOP, "EV-ADV-1", "ORDER_PAID", "EXT-ADV-1",
                "{}", "cipher-adv-1", true, "PULL", "MAPPING_FAILED", 1, "外部SKU未映射", null, now, null, null, 1L);
        YouzanEvent updated = new YouzanEvent(210L, SHOP, "EV-ADV-1", "ORDER_PAID", "EXT-ADV-1",
                "{}", "cipher-adv-1", true, "PULL", "CUSTOMER_UNRESOLVED", 2, "客户尚未人工确认映射", null, now, now, null, 2L);
        YouzanSyncIssue retryingMappingIssue = new YouzanSyncIssue(890L, 210L, "MAPPING_FAILED", "RETRYING",
                "外部SKU未映射", 2, "外部SKU未映射", null, now, null, null, 1L);

        when(repository.findEventForUpdate(TENANT_ID, 210L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-adv-1")).thenReturn(json);
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-ADV-1")).thenReturn(Optional.empty());
        when(repository.findSkuMapping(TENANT_ID, SHOP, "5505:100"))
                .thenReturn(Optional.of(new YouzanSkuMapping(1L, SHOP, "5505:100", 301L, "ACTIVE", now, 0L)));
        when(repository.findCustomerLink(TENANT_ID, SHOP, "EXT-CUST-UNLINKED")).thenReturn(Optional.empty());
        when(repository.updateEventStatus(eq(TENANT_ID), eq(210L), eq(1L), eq("CUSTOMER_UNRESOLVED"),
                anyString(), eq(null), any(Instant.class), eq(null))).thenReturn(1);
        when(repository.findIssueByEventAndType(TENANT_ID, 210L, "MAPPING_FAILED"))
                .thenReturn(Optional.of(retryingMappingIssue));
        when(repository.findIssueByEventAndType(TENANT_ID, 210L, "CUSTOMER_UNRESOLVED")).thenReturn(Optional.empty());
        when(repository.findEvent(TENANT_ID, 210L)).thenReturn(Optional.of(updated));

        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), currentUserService, mock(AuditService.class),
                mock(OutboxService.class), txManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = service.process(TENANT_ID, 210L);
        assertThat(response.processStatus()).isEqualTo("CUSTOMER_UNRESOLVED");
        verify(repository).updateIssueStatus(eq(TENANT_ID), eq(890L), eq(1L), eq("RESOLVED"), eq(2),
                eq(null), eq(null), any(Instant.class), eq(1L));
        verify(repository).insertIssue(eq(TENANT_ID), argThat(issue ->
                "CUSTOMER_UNRESOLVED".equals(issue.issueType()) && "OPEN".equals(issue.status())));

        // 租户无 ADMIN 时不抛异常，仍能以 resolvedBy=null 收敛旧异常
        when(currentUserService.loadFirstActiveByRole(TENANT_ID, "ADMIN")).thenReturn(Optional.empty());
        YouzanEvent rawNoAdmin = new YouzanEvent(211L, SHOP, "EV-ADV-2", "ORDER_PAID", "EXT-ADV-2",
                "{}", "cipher-adv-2", true, "PULL", "MAPPING_FAILED", 1, "外部SKU未映射", null, now, null, null, 1L);
        YouzanEvent updatedNoAdmin = new YouzanEvent(211L, SHOP, "EV-ADV-2", "ORDER_PAID", "EXT-ADV-2",
                "{}", "cipher-adv-2", true, "PULL", "CUSTOMER_UNRESOLVED", 2, "客户尚未人工确认映射", null, now, now, null, 2L);
        YouzanSyncIssue openMappingIssue = new YouzanSyncIssue(891L, 211L, "MAPPING_FAILED", "OPEN",
                "外部SKU未映射", 1, "外部SKU未映射", null, now, null, null, 0L);
        when(repository.findEventForUpdate(TENANT_ID, 211L)).thenReturn(Optional.of(rawNoAdmin));
        when(cipher.decrypt("cipher-adv-2")).thenReturn(json.replace("EV-ADV-1", "EV-ADV-2").replace("EXT-ADV-1", "EXT-ADV-2"));
        when(repository.findOrderMap(TENANT_ID, SHOP, "EXT-ADV-2")).thenReturn(Optional.empty());
        when(repository.updateEventStatus(eq(TENANT_ID), eq(211L), eq(1L), eq("CUSTOMER_UNRESOLVED"),
                anyString(), eq(null), any(Instant.class), eq(null))).thenReturn(1);
        when(repository.findIssueByEventAndType(TENANT_ID, 211L, "MAPPING_FAILED"))
                .thenReturn(Optional.of(openMappingIssue));
        when(repository.findIssueByEventAndType(TENANT_ID, 211L, "CUSTOMER_UNRESOLVED")).thenReturn(Optional.empty());
        when(repository.findEvent(TENANT_ID, 211L)).thenReturn(Optional.of(updatedNoAdmin));

        YouzanEventResponse noAdminResponse = service.process(TENANT_ID, 211L);
        assertThat(noAdminResponse.processStatus()).isEqualTo("CUSTOMER_UNRESOLVED");
        verify(repository).updateIssueStatus(eq(TENANT_ID), eq(891L), eq(0L), eq("RESOLVED"), eq(1),
                eq(null), eq(null), any(Instant.class), eq(null));
    }

    private static Order order(long id, long customerId, String status, long payableFen, long paidFen, long version) {
        return new Order(id, "J00000000000" + id, customerId, "测试客户", 8L, 8L, "EXT-ORD-" + id,
                "YOUZAN", SHOP, "REGULAR", null, null, null, null, null, null, null, null, null,
                status, "PLATFORM", payableFen, 0L, payableFen, paidFen, 0L, Instant.now(), null,
                Instant.now(), version);
    }

    private static PlatformTransactionManager txManager() {
        return new PlatformTransactionManager() {
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
        };
    }
}
