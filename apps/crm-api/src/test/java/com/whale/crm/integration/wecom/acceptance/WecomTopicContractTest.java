package com.whale.crm.integration.wecom.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.apache.kafka.clients.admin.NewTopic;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomTopicContractTest {

    @Test
    void declaresWecomTopic() {
        NewTopic topic = new OutboxKafkaConfiguration().wecomEventsTopic(1, (short) 1);

        assertThat(topic.name()).isEqualTo("crm.wecom.events.v1");
        assertThat(topic.numPartitions()).isEqualTo(1);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    @Test
    void relayUsesWecomTopicAndEnvelopeContainsReferenceWithoutBody() {
        TradingRepository repository = mock(TradingRepository.class);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AtomicReference<String> owner = new AtomicReference<>();
        String payload = "{\"messageRef\":42,\"contentObjectKey\":\"wecom/1/corp/object.json.enc\"}";
        OutboxEvent candidate = event(payload, "NEW", 0, null);
        when(repository.findOutboxTenantIds(any())).thenReturn(List.of(1L));
        when(repository.findPendingOutbox(eq(1L), any(), eq(50))).thenReturn(List.of(candidate));
        when(repository.claimOutboxEvent(eq(1L), eq(9L), eq(0L), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    owner.set(invocation.getArgument(3));
                    return 1;
                });
        when(repository.findOutboxById(1L, 9L)).thenAnswer(invocation ->
                Optional.of(event(payload, "CLAIMED", 1, owner.get())));
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(repository.markOutboxSent(eq(1L), eq(9L), eq(1L), anyString(), any())).thenReturn(1);
        OutboxRelayService relay = new OutboxRelayService(repository, kafka, new ObjectMapper(), false,
                50, 5000, 250, 100, 400);

        assertThat(relay.relayOnce()).isEqualTo(1);

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("crm.wecom.events.v1"), eq("42"), envelope.capture());
        assertThat(envelope.getValue()).contains("\"event_type\":\"wecom.message.archived.v1\"",
                "\"aggregate_type\":\"wecom\"", "\"messageRef\":42",
                "\"contentObjectKey\":\"wecom/1/corp/object.json.enc\"");
        assertThat(envelope.getValue()).doesNotContain("虚构消息正文", "\"text\"", "\"content\"");
    }

    private OutboxEvent event(String payload, String status, long version, String owner) {
        return new OutboxEvent(9L, 1L, "wecom.message.archived.v1:corp:msg-1",
                "wecom.message.archived.v1", 1, "wecom", "42", "42", payload,
                status, 0, null, null, null, owner,
                owner == null ? null : Instant.now().plusSeconds(60), Instant.now(), version);
    }
}
