package com.whale.crm.notification;

import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.notification.infrastructure.NotificationKafkaConfiguration;
import com.whale.crm.notification.infrastructure.wecom.OfficialWecomMessageClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false", "app.outbox.relay.delay-ms=60000",
        "app.notification.kafka.enabled=true", "app.notification.delivery.worker-enabled=false",
        "app.wecom.message.mode=DISABLED"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationBrokerReliabilityIT extends AbstractMysqlIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> KafkaTestContainer.bootstrapServers(KAFKA));
    }

    @Autowired NotificationRequestService requestService;
    @Autowired OutboxRelayService relayService;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean OfficialWecomMessageClient officialClient;

    @Test
    void realBrokerRelaysDeduplicatesAndRejectsInvalidPayloadToDlq() throws Exception {
        awaitListenerReady();
        String suffix = UUID.randomUUID().toString();
        Notification notification = requestService.request(new NotificationRequest(1L, 2L, "FOLLOWUP_DUE",
                "合成 broker 通知", "脱敏正文", "FOLLOWUP", suffix, "broker:" + suffix, "/followups/" + suffix,
                Set.of(NotificationChannel.IN_APP)));
        String eventId = "notification.requested.v1:" + notification.id();
        assertThat(relayService.relayOnce()).isGreaterThanOrEqualTo(1);
        awaitDelivery(notification.id(), "PENDING");
        long inbox = inboxCount(eventId);
        assertThat(inbox).isEqualTo(1);

        jdbcTemplate.update("UPDATE crm_outbox_event SET status='NEW',claim_owner=NULL,claim_expires_at=NULL,"
                + "next_retry_at=NULL WHERE tenant_id=1 AND event_id=?", eventId);
        assertThat(relayService.relayOnce()).isGreaterThanOrEqualTo(1);
        Thread.sleep(500);
        assertThat(inboxCount(eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM crm_notification_delivery WHERE notification_id=?",
                Long.class, notification.id())).isEqualTo(1L);
        verifyNoInteractions(officialClient);

        String invalidEventId = "notification-invalid-" + suffix;
        String invalid = """
                {"event_id":"%s","event_type":"notification.requested.v1","event_version":1,
                "tenant_id":1,"aggregate_type":"notification","aggregate_id":"999999",
                "occurred_at":"2026-01-01T00:00:00Z","correlation_id":"%s",
                "payload":{"notificationId":999999,"contentMasked":"forbidden"}}
                """.formatted(invalidEventId, invalidEventId);
        kafkaTemplate.send(OutboxKafkaConfiguration.NOTIFICATION_REQUEST_TOPIC, invalidEventId, invalid)
                .get(5, TimeUnit.SECONDS);
        ConsumerRecord<String, String> dlq = awaitDlq(invalidEventId);
        assertThat(dlq.value()).isEqualTo(invalid);
        assertThat(header(dlq, "kafka_dlt-original-topic")).isEqualTo(OutboxKafkaConfiguration.NOTIFICATION_REQUEST_TOPIC);
        assertThat(ByteBuffer.wrap(dlq.headers().lastHeader("kafka_dlt-original-offset").value()).getLong())
                .isGreaterThanOrEqualTo(0);
        assertThat(inboxCount(invalidEventId)).isZero();
        verifyNoInteractions(officialClient);
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(container -> container.isRunning() && !container.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("通知 Kafka Listener 分区分配未完成");
    }

    private void awaitDelivery(long notificationId, String expected) throws InterruptedException {
        String status = null;
        for (int attempt = 0; attempt < 300; attempt++) {
            status = jdbcTemplate.queryForObject("SELECT status FROM crm_notification_delivery WHERE notification_id=?",
                    String.class, notificationId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo(expected);
    }

    private long inboxCount(String eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-notification-request' AND event_id=?", Long.class, eventId);
    }

    private ConsumerRecord<String, String> awaitDlq(String key) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        properties.put("group.id", "notification-dlq-inspector-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(NotificationKafkaConfiguration.DLQ_TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("非法通知事件未进入真实 DLQ: " + key);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
