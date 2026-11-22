package com.whale.crm.followup;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false", "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false", "app.followup.automation.scheduler-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FollowupBrokerReliabilityIT extends AbstractMysqlIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry, KAFKA);
    }

    @Autowired
    private OutboxRelayService relayService;

    @Test
    void realBrokerReceivesFollowupEventWithoutSummary() throws Exception {
        AuthCookies manager = login("manager1");
        String customer = mockMvc.perform(manager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"Kafka合成客户\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(customer).at("/data/id").asLong();
        String key = "kafka-followup-" + UUID.randomUUID();
        String followup = mockMvc.perform(manager.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\","
                                + "\"contactAt\":\"2026-01-10T00:00:00Z\",\"summary\":\"禁止进入Kafka正文\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(followup).at("/data/id").asLong();

        assertThat(relayService.relayOnce()).isGreaterThanOrEqualTo(1);
        JsonNode envelope = awaitEvent("followup.recorded.v1:" + followupId, Long.toString(followupId));
        assertThat(envelope.at("/event_type").asText()).isEqualTo("followup.recorded.v1");
        assertThat(envelope.at("/aggregate_type").asText()).isEqualTo("followup");
        JsonNode payload = envelope.at("/payload");
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "followupId", "customerId", "sourceVersion", "staffUserId", "contactAt");
        assertThat(payload.at("/followupId").asLong()).isEqualTo(followupId);
        assertThat(payload.at("/customerId").asLong()).isEqualTo(customerId);
        assertThat(payload.at("/staffUserId").asLong()).isEqualTo(2);
        assertThat(payload.at("/contactAt").asText()).isEqualTo("2026-01-10T00:00:00Z");
        assertThat(envelope.toString()).doesNotContain("禁止进入Kafka正文");
    }

    private JsonNode awaitEvent(String eventId, String partitionKey) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        properties.put("group.id", "followup-event-inspector-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(OutboxKafkaConfiguration.FOLLOWUP_EVENTS_TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    JsonNode envelope = objectMapper.readTree(record.value());
                    if (eventId.equals(envelope.at("/event_id").asText())) {
                        assertThat(record.key()).isEqualTo(partitionKey);
                        assertThat(record.topic()).isEqualTo(OutboxKafkaConfiguration.FOLLOWUP_EVENTS_TOPIC);
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("未收到真实 Kafka 跟进事件: " + eventId);
    }
}
