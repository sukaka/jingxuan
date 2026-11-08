package com.whale.crm.integration.youzan.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanTopicContractTest {

    @Test
    void actualYouzanAggregateUsesYouzanTopic() {
        assertTopic("youzan", "youzan.order.processed.v1", "crm.youzan.events.v1");
    }

    @Test
    void actualYouzanSyncEventsAlsoUseYouzanTopic() {
        assertTopic("youzan", "youzan.sync.issue.v1", "crm.youzan.events.v1");
        assertTopic("youzan", "youzan.sync.completed.v1", "crm.youzan.events.v1");
    }

    @Test
    void orderAndSyncIssueAliasesAlsoUseYouzanTopic() {
        assertTopic("youzan_order", "youzan.order.processed.v1", "crm.youzan.events.v1");
        assertTopic("youzan_sync_issue", "youzan.sync.issue.v1", "crm.youzan.events.v1");
    }

    private void assertTopic(String aggregateType, String eventType, String expectedTopic) {
        TradingRepository repository = mock(TradingRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AtomicReference<String> owner = new AtomicReference<>();
        OutboxEvent candidate = event(aggregateType, eventType, "NEW", 0, null);
        when(repository.findOutboxTenantIds(any())).thenReturn(List.of(7L));
        when(repository.findPendingOutbox(eq(7L), any(), eq(50))).thenReturn(List.of(candidate));
        when(repository.claimOutboxEvent(eq(7L), eq(9L), eq(0L), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    owner.set(invocation.getArgument(3));
                    return 1;
                });
        when(repository.findOutboxById(7L, 9L)).thenAnswer(invocation ->
                Optional.of(event(aggregateType, eventType, "CLAIMED", 1, owner.get())));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markOutboxSent(eq(7L), eq(9L), eq(1L), anyString(), any())).thenReturn(1);
        OutboxRelayService relay = new OutboxRelayService(repository, kafka, new ObjectMapper(), false,
                50, 5000, 250, 100, 400);

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(expectedTopic), eq("9"), envelope.capture());
        assertThat(envelope.getValue()).contains("\"event_id\":\"event-1\"",
                "\"event_type\":\"" + eventType + "\"",
                "\"aggregate_type\":\"" + aggregateType + "\"",
                "\"tenant_id\":7");
    }

    private OutboxEvent event(String aggregateType, String eventType, String status, long version, String owner) {
        return new OutboxEvent(9L, 7L, "event-1", eventType, 1, aggregateType, "9", "9",
                "{}", status, 0, null, null, null, owner,
                owner == null ? null : Instant.now().plusSeconds(60), Instant.now(), version);
    }
}
