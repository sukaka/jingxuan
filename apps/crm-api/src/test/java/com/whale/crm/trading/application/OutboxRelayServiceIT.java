package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
class OutboxRelayServiceIT extends AbstractMysqlIT {
    private static final long TENANT_ID = 990001L;

    @Autowired
    private TradingRepository repository;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTestRows() {
        jdbcTemplate.update("DELETE FROM crm_inbox_event WHERE tenant_id=?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM crm_outbox_event WHERE tenant_id=?", TENANT_ID);
    }

    @Test
    void concurrentClaimHasOnlyOneWinner() throws Exception {
        OutboxEvent event = appendEvent("order", "order.paid.v1");
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String owner = "relay-" + i;
                results.add(executor.submit(() -> {
                    start.await();
                    Instant now = Instant.now();
                    return repository.claimOutboxEvent(TENANT_ID, event.id(), event.version(), owner,
                            now, now.plusSeconds(60));
                }));
            }
            start.countDown();
            int claims = 0;
            for (Future<Integer> result : results) {
                claims += result.get();
            }
            assertThat(claims).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        OutboxEvent claimed = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(claimed.status()).isEqualTo("CLAIMED");
        assertThat(claimed.version()).isEqualTo(1);
        assertThat(claimed.claimOwner()).startsWith("relay-");
        Instant now = Instant.now();
        assertThat(repository.claimOutboxEvent(TENANT_ID, event.id(), claimed.version(), "other-owner",
                now, now.plusSeconds(60))).isZero();
    }

    @Test
    void expiredClaimCanBeRecoveredButActiveClaimCannotBeStolen() {
        OutboxEvent event = appendEvent("order", "order.paid.v1");
        Instant now = Instant.now();
        assertThat(repository.claimOutboxEvent(TENANT_ID, event.id(), event.version(), "owner-a",
                now, now.plusSeconds(60))).isEqualTo(1);
        OutboxEvent claimed = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(repository.claimOutboxEvent(TENANT_ID, event.id(), claimed.version(), "owner-b",
                Instant.now(), Instant.now().plusSeconds(60))).isZero();

        jdbcTemplate.update("UPDATE crm_outbox_event SET claim_expires_at=? WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), TENANT_ID, event.id());
        assertThat(repository.claimOutboxEvent(TENANT_ID, event.id(), claimed.version(), "owner-b",
                Instant.now(), Instant.now().plusSeconds(60))).isEqualTo(1);
        OutboxEvent recovered = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(recovered.claimOwner()).isEqualTo("owner-b");
        assertThat(recovered.version()).isEqualTo(2);
    }

    @Test
    void kafkaFailureIsRetriedAfterBackoffAndThenSent() {
        OutboxEvent event = appendEvent("order", "order.paid.v1");
        AtomicBoolean failTarget = new AtomicBoolean(true);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            if (event.partitionKey().equals(invocation.getArgument(1)) && failTarget.get()) {
                return CompletableFuture.failedFuture(new RuntimeException("simulated outage SECRET-PAYLOAD"));
            }
            return successfulSend();
        });
        OutboxRelayService relay = relay(kafka, "failure-relay");

        Instant beforeFailure = Instant.now();
        relay.relayOnce();
        OutboxEvent failed = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.retryCount()).isEqualTo(1);
        assertThat(failed.lastError()).contains("RuntimeException").doesNotContain("SECRET-PAYLOAD");
        assertThat(failed.nextRetryAt()).isAfter(beforeFailure);
        assertThat(failed.claimOwner()).isNull();
        assertThat(failed.claimExpiresAt()).isNull();

        relay.relayOnce();
        verify(kafka, times(1)).send(eq(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC),
                eq(event.partitionKey()), anyString());

        jdbcTemplate.update("UPDATE crm_outbox_event SET next_retry_at=? WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), TENANT_ID, event.id());
        failTarget.set(false);
        relay.relayOnce();

        OutboxEvent sent = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.retryCount()).isEqualTo(1);
        assertThat(sent.sentAt()).isNotNull();
        assertThat(sent.nextRetryAt()).isNull();
        assertThat(sent.lastError()).isNull();
        verify(kafka, times(2)).send(eq(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC),
                eq(event.partitionKey()), anyString());
    }

    @Test
    void failedEventBeyondOldRetryLimitRemainsEligible() {
        OutboxEvent event = appendEvent("order", "order.paid.v1");
        jdbcTemplate.update("UPDATE crm_outbox_event SET status='FAILED',retry_count=25,next_retry_at=? "
                        + "WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), TENANT_ID, event.id());
        KafkaTemplate<String, String> kafka = successfulKafka();

        relay(kafka, "retry-recovery").relayOnce();

        OutboxEvent sent = repository.findOutboxById(TENANT_ID, event.id()).orElseThrow();
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.retryCount()).isEqualTo(25);
        verify(kafka).send(eq(OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC), eq(event.partitionKey()), anyString());
    }

    @Test
    void longEventIdAndActualTenantSurvivePersistenceAndRelay() throws Exception {
        String eventId = "entitlement.redeemed.v1:" + "k".repeat(128);
        OutboxEvent event = outboxService.append(TENANT_ID, eventId, "entitlement.redeemed.v1",
                "entitlement", "ent-" + UUID.randomUUID(), Map.of("count", 1));
        assertThat(event.eventId()).hasSize(152);
        assertThat(event.tenantId()).isEqualTo(TENANT_ID);
        jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id) VALUES (?,?,?)",
                TENANT_ID, "outbox-relay-it", eventId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=? AND event_id=?",
                Long.class, TENANT_ID, eventId)).isEqualTo(1);

        KafkaTemplate<String, String> kafka = successfulKafka();
        relay(kafka, "long-event-relay").relayOnce();

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(OutboxKafkaConfiguration.ENTITLEMENT_EVENTS_TOPIC),
                eq(event.partitionKey()), envelope.capture());
        JsonNode json = objectMapper.readTree(envelope.getValue());
        assertThat(json.path("event_id").asText()).isEqualTo(eventId);
        assertThat(json.path("tenant_id").asLong()).isEqualTo(TENANT_ID);
        assertThat(repository.findOutboxById(TENANT_ID, event.id()).orElseThrow().status()).isEqualTo("SENT");
    }

    private OutboxEvent appendEvent(String aggregateType, String eventType) {
        return outboxService.append(TENANT_ID, "outbox-it-" + UUID.randomUUID(), eventType,
                aggregateType, "agg-" + UUID.randomUUID(), Map.of("value", "test"));
    }

    private OutboxRelayService relay(KafkaTemplate<String, String> kafka, String owner) {
        return new OutboxRelayService(repository, kafka, objectMapper, false, 50, 5000, 250,
                60000, 120000, owner);
    }

    private KafkaTemplate<String, String> successfulKafka() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> successfulSend());
        return kafka;
    }

    private CompletableFuture<SendResult<String, String>> successfulSend() {
        return CompletableFuture.completedFuture(null);
    }
}
