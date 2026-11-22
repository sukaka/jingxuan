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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
