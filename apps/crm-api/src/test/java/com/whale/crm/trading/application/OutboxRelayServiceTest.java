package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayServiceTest {

    @Test
    void retryDelayIsBoundedExponentialWithJitter() {
        OutboxRelayService service = new OutboxRelayService(mock(TradingRepository.class), mock(KafkaTemplate.class),
                new ObjectMapper(), false, 50, 5000, 250, 100, 400, "unit");
        int[] retryCounts = {1, 2, 3, 10, 100, Integer.MAX_VALUE};
        for (int retryCount : retryCounts) {
            long cap = 100;
            for (int i = 1; i < retryCount && cap < 400; i++) {
                cap = cap > 200 ? 400 : Math.min(400, cap * 2);
            }
            for (int sample = 0; sample < 100; sample++) {
                long delay = service.retryDelayMillis(retryCount);
                assertThat(delay).isBetween(cap - cap / 2, cap);
                assertThat(delay).isLessThanOrEqualTo(400);
            }
        }
    }

    @Test
    void staleSentMarkerIsNotCountedAsSuccessOrRetriedByOldOwner() {
        TradingRepository repository = mock(TradingRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent candidate = event("NEW", 0, null);
        OutboxEvent claimed = event("CLAIMED", 1, "unit");
        when(repository.findOutboxTenantIds(any())).thenReturn(List.of(7L));
        when(repository.findPendingOutbox(eq(7L), any(), eq(50))).thenReturn(List.of(candidate));
        when(repository.claimOutboxEvent(eq(7L), eq(9L), eq(0L), eq("unit"), any(), any())).thenReturn(1);
        when(repository.findOutboxById(7L, 9L)).thenReturn(Optional.of(claimed));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markOutboxSent(eq(7L), eq(9L), eq(1L), eq("unit"), any())).thenReturn(0);
        OutboxRelayService service = new OutboxRelayService(repository, kafka, new ObjectMapper(), false,
                50, 5000, 250, 100, 400, "unit");

        assertThat(service.relayOnce()).isZero();

        verify(repository, never()).markOutboxFailed(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void routesRedemptionEventsAndKeepsExistingMappings() {
        assertTopic("order", "order.paid.v1", "crm.order.events.v1");
        assertTopic("entitlement", "entitlement.redeemed.v1", "crm.entitlement.events.v1");
        assertTopic("refund", "refund.succeeded.v1", "crm.refund.events.v1");
        assertTopic("entitlement", "entitlement.refund_revoked.v1", "crm.entitlement.events.v1");
        assertTopic("settlement", "settlement.payment.recorded.v1", "crm.settlement.events.v1");
        assertTopic("redemption", "redemption.posted.v1", "crm.redemption.events.v1");
        assertTopic("redemption", "redemption.reversed.v1", "crm.redemption.events.v1");
        assertTopic("redemption_reversal", "redemption.reversal.approved.v1", "crm.redemption.events.v1");
        assertTopic("appointment", "appointment.created.v1", "crm.appointment.events.v1");
        assertTopic("appointment", "appointment.status.changed.v1", "crm.appointment.events.v1");
        assertTopic("institution", "institution.contract.created.v1", "crm.institution.events.v1");
        assertTopic("institution_file", "institution.file.cleaned.v1", "crm.institution.events.v1");
        assertTopic("youzan_order", "youzan.order.paid.v1", "crm.youzan.events.v1");
        assertTopic("youzan_sync_issue", "youzan.sync.mapping_failed.v1", "crm.youzan.events.v1");
    }

    private void assertTopic(String aggregateType, String eventType, String expectedTopic) {
        TradingRepository repository = mock(TradingRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        OutboxEvent candidate = event(aggregateType, eventType, "NEW", 0, null);
        OutboxEvent claimed = event(aggregateType, eventType, "CLAIMED", 1, "unit");
        when(repository.findOutboxTenantIds(any())).thenReturn(List.of(7L));
        when(repository.findPendingOutbox(eq(7L), any(), eq(50))).thenReturn(List.of(candidate));
        when(repository.claimOutboxEvent(eq(7L), eq(9L), eq(0L), eq("unit"), any(), any())).thenReturn(1);
        when(repository.findOutboxById(7L, 9L)).thenReturn(Optional.of(claimed));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markOutboxSent(eq(7L), eq(9L), eq(1L), eq("unit"), any())).thenReturn(1);
        OutboxRelayService service = new OutboxRelayService(repository, kafka, new ObjectMapper(), false,
                50, 5000, 250, 100, 400, "unit");

        assertThat(service.relayOnce()).isEqualTo(1);

        verify(kafka).send(eq(expectedTopic), eq("9"), anyString());
    }

    private OutboxEvent event(String status, long version, String owner) {
        return event("order", "order.paid.v1", status, version, owner);
    }

    private OutboxEvent event(String aggregateType, String eventType, String status, long version, String owner) {
        return new OutboxEvent(9L, 7L, "event-1", eventType, 1, aggregateType, "9", "9",
                "{}", status, 0, null, null, null, owner,
                owner == null ? null : Instant.now().plusSeconds(60), Instant.now(), version);
    }
}
