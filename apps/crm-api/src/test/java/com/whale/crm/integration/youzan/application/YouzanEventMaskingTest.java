package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.integration.youzan.domain.YouzanCustomerLink;
import com.whale.crm.integration.youzan.domain.YouzanEvent;
import com.whale.crm.integration.youzan.domain.YouzanRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.trading.application.OrderApplicationService;
import com.whale.crm.trading.application.OutboxService;
import com.whale.crm.trading.application.PaymentApplicationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanEventMaskingTest {
    @Test
    void guideAndExternalIdentityFieldsAreMaskedInPublicPayload() {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findOrderMap(any(Long.class), any(), any())).thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), transactionManager(), mock(TaskExecutor.class));
        YouzanOrderEvent event = MockYouzanAdapter.paidEvent();
        ObjectNode raw = mapper.valueToTree(event);
        raw.put("salesman_mobile", "13800000000");
        raw.put("service_guide_name", "虚构导购");
        raw.put("outer_user_id", "external-open-id");
        raw.put("buyer_message", "含个人联系方式的留言");
        raw.putObject("service_guide").put("service_guide_staff", "staff-private-1");
        java.util.concurrent.atomic.AtomicReference<YouzanEvent> inserted = new java.util.concurrent.atomic.AtomicReference<>();
        when(repository.findEventByKey(any(Long.class), any(), any())).thenAnswer(invocation ->
                inserted.get() == null ? Optional.empty() : Optional.of(inserted.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            inserted.set(invocation.getArgument(1));
            return null;
        }).when(repository).insertEvent(any(Long.class), any());

        service.ingest(2L, event, "OFFICIAL_WEBHOOK", raw.toString());

        ArgumentCaptor<YouzanEvent> saved = ArgumentCaptor.forClass(YouzanEvent.class);
        verify(repository).insertEvent(any(Long.class), saved.capture());
        String masked = saved.getValue().payloadMaskedJson();
        assertThat(masked).doesNotContain("13800000000", "虚构导购", "external-open-id", "staff-private-1",
                "含个人联系方式的留言");
        assertThat(masked).contains("***");
    }

    @Test
    void ingestNormalizesExternalCustomerIdAndCampaignIdAndUpsertsOrderLatest() {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findOrderMap(anyLong(), any(), any())).thenReturn(Optional.empty());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), transactionManager(), mock(TaskExecutor.class));

        String exact128 = "c".repeat(128);
        YouzanOrderEvent validEvent = new YouzanOrderEvent("EV-NORM-1", "ORDER_PAID", "S1", "ORD-NORM-1",
                "  " + exact128 + "  ", "TR-1", "PAID", 1000L, Instant.now(), Instant.now(),
                "REGULAR", 77L, null, "PLATFORM", null, List.of());
        YouzanEvent createdValid = new YouzanEvent(501L, "S1", "EV-NORM-1", "ORDER_PAID", "ORD-NORM-1",
                exact128, 77L, "{}", "cipher", true, "WEBHOOK", "RECEIVED", 0, null, null,
                Instant.now(), null, null, "{}", "pc", 0L);
        when(repository.findEventByKey(1L, "S1", "EV-NORM-1"))
                .thenReturn(Optional.empty(), Optional.of(createdValid));

        service.ingest(1L, validEvent, "WEBHOOK", "{}");

        ArgumentCaptor<YouzanEvent> captor = ArgumentCaptor.forClass(YouzanEvent.class);
        verify(repository).insertEvent(eq(1L), captor.capture());
        assertThat(captor.getValue().externalCustomerId()).isEqualTo(exact128);
        assertThat(captor.getValue().campaignId()).isEqualTo(77L);
        verify(repository).upsertOrderLatest(1L, "S1", "ORD-NORM-1", 501L);

        YouzanOrderEvent invalidEvent = new YouzanOrderEvent("EV-NORM-2", "ORDER_PAID", "S1", "ORD-NORM-2",
                "x".repeat(129), "TR-2", "PAID", 1000L, Instant.now(), Instant.now(),
                "REGULAR", 0L, null, "PLATFORM", null, List.of());
        YouzanEvent createdInvalid = new YouzanEvent(502L, "S1", "EV-NORM-2", "ORDER_PAID", "ORD-NORM-2",
                null, null, "{}", "cipher", true, "WEBHOOK", "RECEIVED", 0, null, null,
                Instant.now(), null, null, "{}", "pc", 0L);
        when(repository.findEventByKey(1L, "S1", "EV-NORM-2"))
                .thenReturn(Optional.empty(), Optional.of(createdInvalid));

        service.ingest(1L, invalidEvent, "WEBHOOK", "{}");

        ArgumentCaptor<YouzanEvent> invalidCaptor = ArgumentCaptor.forClass(YouzanEvent.class);
        verify(repository, org.mockito.Mockito.times(2)).insertEvent(eq(1L), invalidCaptor.capture());
        assertThat(invalidCaptor.getAllValues().get(1).externalCustomerId()).isNull();
        assertThat(invalidCaptor.getAllValues().get(1).campaignId()).isNull();
        verify(repository).upsertOrderLatest(1L, "S1", "ORD-NORM-2", 502L);
    }

    @Test
    void replacePayloadAndProcessUpdatesStructuredColumnsOrderLatestAndLinkedIssues() {
        YouzanRepository repository = mock(YouzanRepository.class);
        AesGcmCipher cipher = mock(AesGcmCipher.class);
        when(cipher.encrypt(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        YouzanRiskLinkOperations riskLinkOperations = mock(YouzanRiskLinkOperations.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YouzanEventService service = new YouzanEventService(repository, mapper, cipher,
                mock(OrderApplicationService.class), mock(PaymentApplicationService.class),
                mock(CustomerScopeSupport.class), mock(CurrentUserService.class), mock(AuditService.class),
                mock(OutboxService.class), transactionManager(), mock(TaskExecutor.class), riskLinkOperations);

        Instant now = Instant.now();
        YouzanEvent raw = new YouzanEvent(610L, "S1", "EV-REP-1", "ORDER_PAID", "ORD-OLD",
                null, null, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "RECEIVED", 0, null, null,
                now, null, null, "{}", "pc", 2L);
        YouzanEvent processedAfter = new YouzanEvent(610L, "S1", "EV-REP-1", "ORDER_PAID", "ORD-NEW",
                "yz-cust-88", 8801L, "{}", "cipher", true, "OFFICIAL_WEBHOOK", "PROCESSED", 1, null, null,
                now, now, 999L, "{}", "pc", 3L);
        when(repository.findEventForUpdate(1L, 610L)).thenReturn(Optional.of(raw), Optional.of(processedAfter));
        when(repository.updateEventPayload(eq(1L), eq(610L), eq(2L), eq("ORD-NEW"), eq("yz-cust-88"), eq(8801L),
                eq("ORDER_PAID"), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(repository.findMaxEventIdByExternalOrder(1L, "S1", "ORD-OLD")).thenReturn(null);
        when(repository.findCustomerLink(1L, "S1", "yz-cust-88")).thenReturn(Optional.of(
                new YouzanCustomerLink(11L, "S1", "yz-cust-88", 1010L, "MANUAL", "HIGH", "ok", 1L, now, 0L)));
        when(repository.findIssueIdsWithoutOrderByExternalCustomer(1L, "S1", "yz-cust-88"))
                .thenReturn(List.of(701L, 702L));

        YouzanOrderEvent detail = new YouzanOrderEvent("EV-REP-1", "ORDER_PAID", "S1", "ORD-NEW",
                "  yz-cust-88  ", "TR-NEW", "PAID", 1000L, now, now, "REGULAR", 8801L, null,
                "PLATFORM", null, List.of(new YouzanOrderLine("SKU-1", 1)));
        service.replacePayloadAndProcess(1L, 610L, detail, "{}");

        verify(repository).updateEventPayload(eq(1L), eq(610L), eq(2L), eq("ORD-NEW"), eq("yz-cust-88"), eq(8801L),
                eq("ORDER_PAID"), anyString(), anyString(), anyString(), anyString());
        verify(repository).upsertOrderLatest(1L, "S1", "ORD-NEW", 610L);
        verify(repository).deleteOrderLatest(1L, "S1", "ORD-OLD", 610L);
        verify(riskLinkOperations).linkCustomerToYouzanIssues(1L, List.of("701", "702"), 1010L);
        verify(repository, never()).upsertOrderLatest(eq(1L), eq("S1"), eq("ORD-OLD"), anyLong());
        verify(repository, never()).updateEventPayload(anyLong(), anyLong(), anyLong(), isNull(), any(), any(), any(),
                any(), any(), any(), any());
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
