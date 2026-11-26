package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.adapter.YouzanPullPage;
import com.whale.crm.integration.youzan.adapter.YouzanSyncCursorValue;
import com.whale.crm.integration.youzan.adapter.YouzanWindowTooLargeException;
import com.whale.crm.integration.youzan.api.dto.YouzanEventResponse;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanSyncResponse;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanOrderMap;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import com.whale.crm.integration.youzan.domain.YouzanSyncCursor;
import com.whale.crm.integration.youzan.domain.YouzanSyncIssue;
import com.whale.crm.integration.youzan.domain.YouzanSyncJob;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.application.PaymentApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanManagementWindowTest {
    @Test
    void oversizedWindowSplitsAndKeepsSecondHalfCursor() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(eq(2L), eq("123"))).thenReturn(Optional.empty());
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Instant end = Instant.parse("2026-03-01T00:00:00Z");
        String cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start, end, end, 100));
        when(adapter.pullOrders(eq(2L), eq("123"), anyString(), eq(100)))
                .thenThrow(new YouzanWindowTooLargeException())
                .thenAnswer(invocation -> {
                    String value = invocation.getArgument(2);
                    return new YouzanPullPage(List.of(), value, false, Map.of());
                });
        YouzanManagementService service = managementService(repository, configs, mock(YouzanEventService.class),
                router, mapper, transactionManager());
        CurrentUser user = admin(1, 2);

        YouzanSyncResponse response = service.sync(user, new YouzanSyncRequest("123", cursor, 100), "window-test");

        YouzanSyncCursorValue next = mapper.readValue(response.nextCursor(), YouzanSyncCursorValue.class);
        assertThat(next.windowStart()).isEqualTo(Instant.parse("2026-01-30T12:00:00Z"));
        assertThat(next.windowEnd()).isEqualTo(end);
        assertThat(next.pageNo()).isEqualTo(1);
        assertThat(response.hasMore()).isTrue();
    }

    @Test
    void syncScheduledLoopsAcrossPagesAndSubWindowsAdvancesLastSyncSafelyAndRenewsJobLease() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "cli", "sec",
                "tok", "ref", Instant.parse("2026-12-01T00:00:00Z"), "CONNECTED", true, null, null, null, 0L);
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.of(shop));
        when(configs.acquireShopLease(eq(2L), eq("123"), anyString(), any(Instant.class))).thenReturn(1);
        when(configs.renewSyncJobLease(eq(2L), eq(55L), anyString(), any(Instant.class))).thenReturn(1);
        AtomicReference<String> leaseOwnerRef = new AtomicReference<>();
        when(configs.findSyncJobByLeaseOwner(eq(2L), anyString())).thenAnswer(inv -> {
            String owner = inv.getArgument(1);
            leaseOwnerRef.set(owner);
            return Optional.of(new YouzanSyncJob(55L, "123", "INCREMENTAL", "RUNNING", null, null, null,
                    0, 0, 0, 0, null, owner, Instant.now().plusSeconds(300), null, Instant.now(), null, 0L));
        });
        when(configs.findSyncJob(2L, 55L)).thenAnswer(inv -> Optional.of(new YouzanSyncJob(55L, "123", "INCREMENTAL",
                "RUNNING", null, null, null, 0, 0, 0, 0, null, leaseOwnerRef.get(),
                Instant.now().plusSeconds(300), null, Instant.now(), null, 3L)));
        when(router.adapter(2L, "123")).thenReturn(adapter);

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-04-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T00:00:00Z");
        AtomicReference<YouzanSyncCursor> storedCursor = new AtomicReference<>(
                new YouzanSyncCursor(9L, "123", "{}", t0, t0, t0, Instant.parse("2025-12-31T23:00:00Z"), null, 0L));
        List<Instant> savedLastSyncs = new ArrayList<>();
        when(repository.findCursorForUpdate(2L, "123")).thenAnswer(inv -> Optional.of(storedCursor.get()));
        when(repository.updateCursor(eq(2L), eq(9L), anyLong(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    YouzanSyncCursor current = storedCursor.get();
                    Instant lastSync = inv.getArgument(6);
                    savedLastSyncs.add(lastSync);
                    storedCursor.set(new YouzanSyncCursor(current.id(), current.shopId(), inv.getArgument(3),
                            inv.getArgument(4), inv.getArgument(5), lastSync, inv.getArgument(7),
                            current.createdAt(), current.version() + 1));
                    return 1;
                });

        YouzanOrderEvent ev1 = sampleEvent("EV-1", "O-1", Instant.parse("2026-02-01T00:00:00Z"));
        YouzanOrderEvent ev2 = sampleEvent("EV-2", "O-2", Instant.parse("2026-03-01T00:00:00Z"));
        YouzanOrderEvent ev3 = sampleEvent("EV-3", "O-3", Instant.parse("2026-04-15T00:00:00Z"));
        when(repository.findEventByKey(2L, "123", "EV-1")).thenReturn(Optional.of(rawEvent(101L, "EV-1", "O-1")));
        when(repository.findEventByKey(2L, "123", "EV-2")).thenReturn(Optional.of(rawEvent(102L, "EV-2", "O-2")));
        when(repository.findEventByKey(2L, "123", "EV-3")).thenReturn(Optional.of(rawEvent(103L, "EV-3", "O-3")));
        when(eventService.process(2L, 101L)).thenReturn(new YouzanEventResponse(101L, "EV-1", "ORDER_PAID", "O-1",
                "PROCESSED", 1, null, 1001L, t0, t0, false));
        when(eventService.process(2L, 102L)).thenReturn(new YouzanEventResponse(102L, "EV-2", "ORDER_PAID", "O-2",
                "PROCESSED", 1, null, 1002L, t0, t0, true));
        when(eventService.process(2L, 103L)).thenReturn(new YouzanEventResponse(103L, "EV-3", "ORDER_PAID", "O-3",
                "MAPPING_FAILED", 1, "missing sku", null, t0, t0, false));

        String c1 = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", t0, t1, t2, 1));
        String c1Page2 = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", t0, t1, t2, 2));
        String c2 = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", t1, t2, t2, 1));
        when(adapter.pullOrders(2L, "123", c1, 50)).thenReturn(new YouzanPullPage(List.of(ev1), c1Page2, true, Map.of()));
        when(adapter.pullOrders(2L, "123", c1Page2, 50)).thenReturn(new YouzanPullPage(List.of(ev2), c1Page2, false, Map.of()));
        when(adapter.pullOrders(2L, "123", c2, 50)).thenReturn(new YouzanPullPage(List.of(ev3), c2, false, Map.of()));

        YouzanManagementService service = managementService(repository, configs, eventService, router, mapper,
                transactionManager());

        YouzanSyncResponse total = service.syncScheduled(2L, "123", c1, 50);

        assertThat(total.received()).isEqualTo(3);
        assertThat(total.processed()).isEqualTo(2);
        assertThat(total.duplicates()).isEqualTo(1);
        assertThat(total.issues()).isEqualTo(1);
        assertThat(total.hasMore()).isFalse();
        assertThat(savedLastSyncs).containsExactly(t0, t1, t2);
        verify(configs, times(4)).acquireShopLease(eq(2L), eq("123"), eq(leaseOwnerRef.get()), any(Instant.class));
        verify(configs, times(3)).renewSyncJobLease(eq(2L), eq(55L), eq(leaseOwnerRef.get()), any(Instant.class));
        verify(configs).releaseShopLease(2L, "123", leaseOwnerRef.get());
    }

    @Test
    void saveCursorDoesNotAdvanceLastSyncAtOnUnfinishedPageAndPreservesLastExternalUpdatedAtWhenLatestIsNull() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        Instant existingLastSync = Instant.parse("2026-02-01T00:00:00Z");
        Instant existingExternalUpdated = Instant.parse("2026-01-31T23:55:00Z");
        Instant windowStart = Instant.parse("2026-02-01T00:00:00Z");
        Instant windowEnd = Instant.parse("2026-02-02T00:00:00Z");
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(2L, "123")).thenReturn(Optional.of(
                new YouzanSyncCursor(7L, "123", "{}", existingLastSync, existingLastSync,
                        existingLastSync, existingExternalUpdated, null, 2L)));
        when(repository.updateCursor(eq(2L), eq(7L), eq(2L), anyString(), eq(windowStart), eq(windowEnd),
                eq(existingLastSync), eq(existingExternalUpdated))).thenReturn(1);

        String page1Cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", windowStart, windowEnd, windowEnd, 1));
        String page2Cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", windowStart, windowEnd, windowEnd, 2));
        when(adapter.pullOrders(2L, "123", page1Cursor, 50))
                .thenReturn(new YouzanPullPage(List.of(), page2Cursor, true, Map.of()));

        YouzanManagementService service = managementService(repository, configs, mock(YouzanEventService.class),
                router, mapper, transactionManager());

        YouzanSyncResponse response = service.sync(admin(1, 2), new YouzanSyncRequest("123", page1Cursor, 50), "cursor-preserve");

        assertThat(response.hasMore()).isTrue();
        verify(repository).updateCursor(eq(2L), eq(7L), eq(2L), eq(page2Cursor), eq(windowStart), eq(windowEnd),
                eq(existingLastSync), eq(existingExternalUpdated));
    }

    @Test
    void oversizedWindowBisectsOnWholeSecondsAndStopsAtOneSecondOrSubSecond() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(2L, "123")).thenReturn(Optional.empty());
        YouzanManagementService service = managementService(repository, configs, mock(YouzanEventService.class),
                router, mapper, transactionManager());

        Instant start3s = Instant.parse("2026-01-01T00:00:00Z");
        Instant mid3s = Instant.parse("2026-01-01T00:00:01Z");
        Instant end3s = Instant.parse("2026-01-01T00:00:03Z");
        String cursor3s = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start3s, end3s, end3s, 100));
        String firstHalf3s = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", start3s, mid3s, end3s, 1));
        when(adapter.pullOrders(2L, "123", cursor3s, 100)).thenThrow(new YouzanWindowTooLargeException());
        when(adapter.pullOrders(2L, "123", firstHalf3s, 100))
                .thenReturn(new YouzanPullPage(List.of(), firstHalf3s, false, Map.of()));
        YouzanSyncResponse split = service.sync(admin(1, 2), new YouzanSyncRequest("123", cursor3s, 100), "split-3s");
        YouzanSyncCursorValue next = mapper.readValue(split.nextCursor(), YouzanSyncCursorValue.class);
        assertThat(next.windowStart()).isEqualTo(mid3s);
        assertThat(next.windowEnd()).isEqualTo(end3s);
        assertThat(next.windowStart().getNano()).isZero();

        String cursor1s = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("2026-01-01T00:00:01Z"), 100));
        when(adapter.pullOrders(2L, "123", cursor1s, 100)).thenThrow(new YouzanWindowTooLargeException());
        assertThatThrownBy(() -> service.sync(admin(1, 2), new YouzanSyncRequest("123", cursor1s, 100), "split-1s"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.PENDING_BUSINESS_RULE));

        String cursor1500ms = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:01.500Z"),
                Instant.parse("2026-01-01T00:00:01.500Z"), 100));
        when(adapter.pullOrders(2L, "123", cursor1500ms, 100)).thenThrow(new YouzanWindowTooLargeException());
        assertThatThrownBy(() -> service.sync(admin(1, 2), new YouzanSyncRequest("123", cursor1500ms, 100), "split-1500ms"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.PENDING_BUSINESS_RULE));
    }

    @Test
    void retryIssueHoldsRowLockInsideTransactionBeforeProcessingEvent() {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        AuditService auditService = mock(AuditService.class);
        AtomicBoolean inTransaction = new AtomicBoolean(false);
        PlatformTransactionManager txManager = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                inTransaction.set(true);
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
                inTransaction.set(false);
            }

            public void rollback(TransactionStatus status) {
                inTransaction.set(false);
            }
        };
        YouzanSyncIssue issue = new YouzanSyncIssue(88L, 999L, "MAPPING_FAILED", "OPEN", "missing",
                1, "missing", null, Instant.now(), null, null, 2L);
        when(repository.findIssueForUpdate(2L, 88L)).thenAnswer(inv -> {
            assertThat(inTransaction.get()).isTrue();
            return Optional.of(issue);
        });
        when(repository.updateIssueStatus(eq(2L), eq(88L), eq(2L), eq("RETRYING"), eq(2),
                eq("missing"), isNull(), isNull(), isNull())).thenAnswer(inv -> {
            assertThat(inTransaction.get()).isTrue();
            return 1;
        });
        YouzanEventResponse processed = new YouzanEventResponse(999L, "EV-999", "ORDER_PAID", "O-999",
                "PROCESSED", 2, null, 500L, Instant.now(), Instant.now(), false);
        when(eventService.process(2L, 999L)).thenAnswer(inv -> {
            assertThat(inTransaction.get()).isFalse();
            return processed;
        });
        YouzanManagementService service = new YouzanManagementService(repository, mock(YouzanConfigRepository.class),
                eventService, mock(YouzanAdapterRouter.class), mock(OrderApplicationService.class),
                mock(CatalogApplicationService.class), mock(CustomerScopeSupport.class),
                mock(IdempotencyService.class), auditService, mock(OutboxService.class),
                new ObjectMapper().findAndRegisterModules(), mock(AesGcmCipher.class), txManager);

        YouzanEventResponse result = service.retryIssue(admin(42L, 2L), 88L);

        assertThat(result).isEqualTo(processed);
        verify(auditService).record(eq(2L), eq(Long.valueOf(42L)), eq("YOUZAN_ISSUE_RETRY"), eq("YOUZAN"), eq("999"),
                isNull(), eq(processed), isNull());
    }

    @Test
    void eventServiceCompleteUsesSystemAdminUserIdInsteadOfTenantId() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        AuditService auditService = mock(AuditService.class);
        OutboxService outboxService = mock(OutboxService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        long tenantId = 7L;
        long adminUserId = 701L;
        Instant updatedAt = Instant.parse("2026-03-01T10:00:00Z");
        YouzanOrderEvent orderEvent = sampleEvent("EV-ACTOR-1", "ORD-ACTOR-1", updatedAt);
        String normalizedJson = mapper.writeValueAsString(orderEvent);
        YouzanEvent raw = new YouzanEvent(301L, "123", "EV-ACTOR-1", "ORDER_PAID", "ORD-ACTOR-1",
                "{}", "cipher-payload", true, "PULL", "RECEIVED", 0, null, null,
                updatedAt, null, null, 0L);
        YouzanEvent done = new YouzanEvent(301L, "123", "EV-ACTOR-1", "ORDER_PAID", "ORD-ACTOR-1",
                "{}", "cipher-payload", true, "PULL", "PROCESSED", 1, null, null,
                updatedAt, updatedAt, 9001L, 1L);
        YouzanOrderMap orderMap = new YouzanOrderMap(401L, "123", "ORD-ACTOR-1", 9001L, updatedAt, updatedAt, 0L);
        YouzanSyncIssue openIssue = new YouzanSyncIssue(501L, 301L, "MAPPING_FAILED", "OPEN", "err",
                1, "err", null, updatedAt, null, null, 0L);

        when(repository.findEventForUpdate(tenantId, 301L)).thenReturn(Optional.of(raw));
        when(cipher.decrypt("cipher-payload")).thenReturn(normalizedJson);
        when(repository.findOrderMap(tenantId, "123", "ORD-ACTOR-1")).thenReturn(Optional.of(orderMap));
        when(repository.findOrderMapForUpdate(tenantId, "123", "ORD-ACTOR-1")).thenReturn(Optional.of(orderMap));
        when(repository.updateEventStatus(eq(tenantId), eq(301L), eq(0L), eq("PROCESSED"), isNull(), isNull(),
                any(Instant.class), eq(Long.valueOf(9001L)))).thenReturn(1);
        when(repository.findIssueByEventAndType(tenantId, 301L, "MAPPING_FAILED")).thenReturn(Optional.of(openIssue));
        when(repository.findIssueByEventAndType(tenantId, 301L, "CUSTOMER_UNRESOLVED")).thenReturn(Optional.empty());
        when(repository.findIssueByEventAndType(tenantId, 301L, "PAYMENT_MISMATCH")).thenReturn(Optional.empty());
        when(repository.findIssueByEventAndType(tenantId, 301L, "RETRY")).thenReturn(Optional.empty());
        when(repository.findEvent(tenantId, 301L)).thenReturn(Optional.of(done));
        when(currentUserService.loadFirstActiveByRole(tenantId, "ADMIN")).thenReturn(Optional.of(admin(adminUserId, tenantId)));

        YouzanEventService eventService = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), currentUserService, auditService, outboxService,
                transactionManager(), mock(TaskExecutor.class));

        YouzanEventResponse response = eventService.process(tenantId, 301L);

        assertThat(response.processStatus()).isEqualTo("PROCESSED");
        verify(repository).updateIssueStatus(eq(tenantId), eq(501L), eq(0L), eq("RESOLVED"), eq(1),
                isNull(), isNull(), any(Instant.class), eq(Long.valueOf(adminUserId)));
        verify(auditService).record(eq(tenantId), eq(Long.valueOf(adminUserId)), eq("YOUZAN_ORDER_PROCESSED"), eq("ORDER"),
                eq("9001"), isNull(), any(), isNull());
    }

    @Test
    void renewSyncJobLeaseFailureThrowsConflictBeforeSaveCursorAndSkipsUpdatingFailedJob() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "cli", "sec",
                "tok", "ref", Instant.parse("2026-12-01T00:00:00Z"), "CONNECTED", true, null, null, null, 0L);
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.of(shop));
        when(configs.acquireShopLease(eq(2L), eq("123"), anyString(), any(Instant.class))).thenReturn(1);
        when(configs.renewSyncJobLease(eq(2L), eq(55L), anyString(), any(Instant.class))).thenReturn(0);
        AtomicReference<String> leaseOwnerRef = new AtomicReference<>();
        when(configs.findSyncJobByLeaseOwner(eq(2L), anyString())).thenAnswer(inv -> {
            String owner = inv.getArgument(1);
            leaseOwnerRef.set(owner);
            return Optional.of(new YouzanSyncJob(55L, "123", "INCREMENTAL", "RUNNING", null, null, null,
                    0, 0, 0, 0, null, owner, Instant.now().plusSeconds(300), null, Instant.now(), null, 0L));
        });
        when(configs.findSyncJob(2L, 55L)).thenReturn(Optional.of(new YouzanSyncJob(55L, "123", "INCREMENTAL",
                "FAILED", null, null, null, 0, 0, 0, 0, "同步执行实例中断，任务已按过期租约关闭", null, null,
                null, Instant.now(), Instant.now(), 1L)));
        when(router.adapter(2L, "123")).thenReturn(adapter);

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        String cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", t0, t1, t1, 1));
        when(adapter.pullOrders(2L, "123", cursor, 50)).thenReturn(new YouzanPullPage(List.of(), cursor, false, Map.of()));

        YouzanManagementService service = managementService(repository, configs, mock(YouzanEventService.class),
                router, mapper, transactionManager());

        assertThatThrownBy(() -> service.syncScheduled(2L, "123", cursor, 50))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT));

        verify(repository, never()).insertCursor(anyLong(), any());
        verify(repository, never()).updateCursor(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        verify(configs, never()).updateSyncJob(anyLong(), anyLong(), anyLong(), any());
        verify(configs).releaseShopLease(2L, "123", leaseOwnerRef.get());
    }

    @Test
    void finishJobSkipsUpdatingAlreadyExpiredFailedJob() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        YouzanShopConfig shop = new YouzanShopConfig(1L, 2L, "123", "测试店", "cli", "sec",
                "tok", "ref", Instant.parse("2026-12-01T00:00:00Z"), "CONNECTED", true, null, null, null, 0L);
        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.of(shop));
        when(configs.acquireShopLease(eq(2L), eq("123"), anyString(), any(Instant.class))).thenReturn(1);
        when(configs.renewSyncJobLease(eq(2L), eq(56L), anyString(), any(Instant.class))).thenReturn(1);
        AtomicReference<String> leaseOwnerRef = new AtomicReference<>();
        when(configs.findSyncJobByLeaseOwner(eq(2L), anyString())).thenAnswer(inv -> {
            String owner = inv.getArgument(1);
            leaseOwnerRef.set(owner);
            return Optional.of(new YouzanSyncJob(56L, "123", "INCREMENTAL", "RUNNING", null, null, null,
                    0, 0, 0, 0, null, owner, Instant.now().plusSeconds(300), null, Instant.now(), null, 0L));
        });
        when(configs.findSyncJob(2L, 56L)).thenReturn(Optional.of(new YouzanSyncJob(56L, "123", "INCREMENTAL",
                "FAILED", null, null, null, 0, 0, 0, 0, "同步执行实例中断，任务已按过期租约关闭", null, null,
                null, Instant.now(), Instant.now(), 2L)));
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(2L, "123")).thenReturn(Optional.empty());

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        String cursor = mapper.writeValueAsString(new YouzanSyncCursorValue("UPDATED", t0, t1, t1, 1));
        when(adapter.pullOrders(2L, "123", cursor, 50)).thenReturn(new YouzanPullPage(List.of(), cursor, false, Map.of()));

        YouzanManagementService service = managementService(repository, configs, mock(YouzanEventService.class),
                router, mapper, transactionManager());

        service.syncScheduled(2L, "123", cursor, 50);

        verify(configs, never()).updateSyncJob(anyLong(), anyLong(), anyLong(), any());
        verify(configs).releaseShopLease(2L, "123", leaseOwnerRef.get());
    }

    @Test
    void eventServiceRetryLaterAndPayloadUpdateDoNotRegressProcessedOrClassifiedEvents() {
        YouzanRepository repository = mock(YouzanRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant processedAt = Instant.parse("2026-03-01T10:00:00Z");
        YouzanEvent processedEvent = new YouzanEvent(601L, "123", "EV-DONE", "ORDER_PAID", "ORD-DONE",
                "{}", "cipher", true, "OFFICIAL_WEBHOOK", "PROCESSED", 1, null, null,
                processedAt, processedAt, 9001L, 2L);
        YouzanEvent mappingFailedEvent = new YouzanEvent(602L, "123", "EV-MAP", "ORDER_PAID", "ORD-MAP",
                "{}", "cipher", true, "OFFICIAL_WEBHOOK", "MAPPING_FAILED", 1, "missing sku", null,
                processedAt, processedAt, null, 1L);
        when(repository.findEventForUpdate(2L, 601L)).thenReturn(Optional.of(processedEvent));
        when(repository.findEventForUpdate(2L, 602L)).thenReturn(Optional.of(mappingFailedEvent));

        YouzanEventService eventService = new YouzanEventService(repository, mapper, mock(AesGcmCipher.class),
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), transactionManager(), mock(TaskExecutor.class));

        YouzanEventResponse processedRetry = eventService.retryLater(2L, 601L, new RuntimeException("transient"));
        assertThat(processedRetry.processStatus()).isEqualTo("PROCESSED");
        assertThat(processedRetry.duplicate()).isTrue();
        assertThat(processedRetry.processedAt()).isEqualTo(processedAt);

        YouzanEventResponse classifiedRetry = eventService.retryLater(2L, 602L, new RuntimeException("transient"));
        assertThat(classifiedRetry.processStatus()).isEqualTo("MAPPING_FAILED");
        assertThat(classifiedRetry.duplicate()).isFalse();

        YouzanEventResponse replaced = eventService.replacePayloadAndProcess(2L, 601L,
                sampleEvent("EV-DONE", "ORD-DONE", processedAt), "{}");
        assertThat(replaced.processStatus()).isEqualTo("PROCESSED");
        assertThat(replaced.duplicate()).isTrue();

        verify(repository, never()).updateEventStatus(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any());
        verify(repository, never()).updateEventPayload(anyLong(), anyLong(), anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(repository, never()).insertIssue(anyLong(), any());
        verify(repository, never()).updateIssueStatus(anyLong(), anyLong(), anyLong(), any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void reconcileRespectsMaxOrdersSkipsEmptyWindowsAndStopsWithoutChangingPageSize() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(2L, "123")).thenReturn(Optional.empty());

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-04-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T00:00:00Z");
        String emptyWin = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", t0, t1, t2, 1));
        String win2Page1 = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", t1, t2, t2, 1));
        String win2Page2 = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", t1, t2, t2, 2));

        List<YouzanOrderEvent> tenOrders = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String evId = "EV-CAP-" + i;
            String ordNo = "ORD-CAP-" + i;
            tenOrders.add(sampleEvent(evId, ordNo, Instant.parse("2026-04-10T00:00:00Z").plusSeconds(i)));
            long dbId = 800L + i;
            when(repository.findEventByKey(2L, "123", evId)).thenReturn(Optional.of(rawEvent(dbId, evId, ordNo)));
            when(eventService.process(2L, dbId)).thenReturn(new YouzanEventResponse(dbId, evId, "ORDER_PAID", ordNo,
                    "PROCESSED", 1, null, 9000L + i, t1, t1, false));
        }
        when(adapter.pullOrders(2L, "123", emptyWin, 10))
                .thenReturn(new YouzanPullPage(List.of(), emptyWin, false, Map.of()));
        when(adapter.pullOrders(2L, "123", win2Page1, 10))
                .thenReturn(new YouzanPullPage(tenOrders, win2Page2, true, Map.of()));

        YouzanManagementService service = managementService(repository, configs, eventService, router, mapper,
                transactionManager());

        YouzanSyncResponse response = service.reconcile(admin(1, 2),
                new YouzanSyncRequest("123", emptyWin, 10, 10), "reconcile-cap-10");

        assertThat(response.received()).isEqualTo(10);
        assertThat(response.processed()).isEqualTo(10);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(win2Page2);
        verify(adapter, times(1)).pullOrders(2L, "123", emptyWin, 10);
        verify(adapter, times(1)).pullOrders(2L, "123", win2Page1, 10);
        verify(adapter, never()).pullOrders(2L, "123", win2Page2, 10);
    }

    @Test
    void reconcileStopsBeforePartialNextPageWhenRemainingLessThanPageLimit() throws Exception {
        YouzanRepository repository = mock(YouzanRepository.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        YouzanEventService eventService = mock(YouzanEventService.class);
        YouzanAdapterRouter router = mock(YouzanAdapterRouter.class);
        RealYouzanAdapter adapter = mock(RealYouzanAdapter.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        when(configs.findShopConfig(2L, "123")).thenReturn(Optional.empty());
        when(router.adapter(2L, "123")).thenReturn(adapter);
        when(repository.findCursorForUpdate(2L, "123")).thenReturn(Optional.empty());

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-04-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-05-01T00:00:00Z");
        String win1 = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", t0, t1, t2, 1));
        String win2 = mapper.writeValueAsString(new YouzanSyncCursorValue("CREATED", t1, t2, t2, 1));

        List<YouzanOrderEvent> threeOrders = List.of(
                sampleEvent("EV-P-1", "ORD-P-1", Instant.parse("2026-02-01T00:00:00Z")),
                sampleEvent("EV-P-2", "ORD-P-2", Instant.parse("2026-02-02T00:00:00Z")),
                sampleEvent("EV-P-3", "ORD-P-3", Instant.parse("2026-02-03T00:00:00Z")));
        for (int i = 1; i <= 3; i++) {
            String evId = "EV-P-" + i;
            String ordNo = "ORD-P-" + i;
            long dbId = 850L + i;
            when(repository.findEventByKey(2L, "123", evId)).thenReturn(Optional.of(rawEvent(dbId, evId, ordNo)));
            when(eventService.process(2L, dbId)).thenReturn(new YouzanEventResponse(dbId, evId, "ORDER_PAID", ordNo,
                    "PROCESSED", 1, null, 9500L + i, t0, t0, false));
        }
        when(adapter.pullOrders(2L, "123", win1, 10))
                .thenReturn(new YouzanPullPage(threeOrders, win1, false, Map.of()));

        YouzanManagementService service = managementService(repository, configs, eventService, router, mapper,
                transactionManager());

        YouzanSyncResponse response = service.reconcile(admin(1, 2),
                new YouzanSyncRequest("123", win1, 10, 10), "reconcile-no-overshoot");

        assertThat(response.received()).isEqualTo(3);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(win2);
        verify(adapter, times(1)).pullOrders(2L, "123", win1, 10);
        verify(adapter, never()).pullOrders(eq(2L), eq("123"), eq(win2), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void resolvePageLimitsChoosesSafeDivisorForMultiPageMaxOrders() {
        assertThat(YouzanManagementService.resolveSyncPageLimit(null, null)).isEqualTo(50);
        assertThat(YouzanManagementService.resolveSyncPageLimit(20, null)).isEqualTo(20);
        assertThat(YouzanManagementService.resolveSyncPageLimit(null, 10)).isEqualTo(10);
        assertThat(YouzanManagementService.resolveSyncPageLimit(50, 10)).isEqualTo(10);
        assertThat(YouzanManagementService.resolveSyncPageLimit(null, 200)).isEqualTo(100);

        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, null)).isEqualTo(50);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(50, null)).isEqualTo(50);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 10)).isEqualTo(10);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 20)).isEqualTo(20);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(50, 20)).isEqualTo(20);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 150)).isEqualTo(75);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 200)).isEqualTo(100);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 500)).isEqualTo(100);
        assertThat(YouzanManagementService.resolveReconcilePageLimit(null, 127)).isEqualTo(50);
    }

    private static YouzanManagementService managementService(YouzanRepository repository,
                                                             YouzanConfigRepository configs,
                                                             YouzanEventService eventService,
                                                             YouzanAdapterRouter router,
                                                             ObjectMapper mapper,
                                                             PlatformTransactionManager txManager) {
        return new YouzanManagementService(repository, configs, eventService, router,
                mock(OrderApplicationService.class), mock(CatalogApplicationService.class),
                mock(CustomerScopeSupport.class), mock(IdempotencyService.class), mock(AuditService.class),
                mock(OutboxService.class), mapper, mock(AesGcmCipher.class), txManager);
    }

    private static CurrentUser admin(long userId, long tenantId) {
        return new CurrentUser(userId, tenantId, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("integration:youzan:manage"), Set.of());
    }

    private static YouzanOrderEvent sampleEvent(String eventId, String orderNo, Instant updatedAt) {
        return new YouzanOrderEvent(eventId, "ORDER_PAID", "123", orderNo, "cust-1", "trade-" + eventId,
                "PAID", 10000L, updatedAt, updatedAt, "REGULAR", null, null, "PLATFORM",
                LocalDate.parse("2027-01-01"), List.of(new YouzanOrderLine("sku-1", 1)));
    }

    private static YouzanEvent rawEvent(long id, String eventId, String orderNo) {
        return new YouzanEvent(id, "123", eventId, "ORDER_PAID", orderNo, "{}", "cipher", true,
                "PULL", "RECEIVED", 0, null, null, Instant.now(), null, null, 0L);
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            public void commit(TransactionStatus status) {
            }

            public void rollback(TransactionStatus status) {
            }
        };
    }
}
