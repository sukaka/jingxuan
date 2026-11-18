package com.whale.crm.complaint;

import com.whale.crm.complaint.application.ComplaintEventConsumer;
import com.whale.crm.complaint.infrastructure.ComplaintKafkaConfiguration;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.support.KafkaTestContainer;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.complaint.kafka.enabled=true", "app.outbox.relay.enabled=false",
        "app.notification.kafka.enabled=false", "app.notification.delivery.worker-enabled=false",
        "app.wecom.message.mode=DISABLED", "app.ai.worker.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ComplaintBrokerReliabilityIT extends AbstractMysqlIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> KafkaTestContainer.bootstrapServers(KAFKA));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired
    private AesGcmCipher cipher;
    @MockitoBean
    private PrivateObjectStorage objectStorage;

    @Test
    void realBrokerDeduplicatesWecomAndFollowupAndPublishesInvalidPayloadToDlq() throws Exception {
        awaitListenerReady();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String keyword = "投诉" + suffix;
        jdbcTemplate.update("INSERT INTO crm_complaint_keyword (tenant_id,keyword,status,created_by) "
                + "VALUES (1,?,'ACTIVE',1)", keyword);
        long customerId = newCustomer("IT-COMPLAINT-BROKER-" + suffix);
        WecomFixture fixture = newWecom(suffix, customerId, "我要" + keyword + "，电话13800138000");
        String wecomEventId = "complaint-wecom-" + suffix;
        kafkaTemplate.send(OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, wecomEventId,
                envelope(wecomEventId, ComplaintEventConsumer.WECOM_EVENT, "wecom", fixture.messageId(),
                        fixture.payload())).get(5, TimeUnit.SECONDS);
        awaitCount("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 AND source_type='WECOM_MESSAGE' "
                + "AND source_id=?", 1, Long.toString(fixture.messageId()));
        kafkaTemplate.send(OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, wecomEventId,
                envelope(wecomEventId, ComplaintEventConsumer.WECOM_EVENT, "wecom", fixture.messageId(),
                        fixture.payload())).get(5, TimeUnit.SECONDS);
        Thread.sleep(600);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 AND source_type='WECOM_MESSAGE' "
                + "AND source_id=?", Long.toString(fixture.messageId()))).isEqualTo(1);
        assertThat(inboxCount(ComplaintEventConsumer.WECOM_GROUP, wecomEventId)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_case cc JOIN crm_complaint_hit h "
                + "ON h.tenant_id=cc.tenant_id AND h.id=cc.complaint_hit_id WHERE cc.tenant_id=1 "
                + "AND h.source_type='WECOM_MESSAGE' AND h.source_id=?", Long.toString(fixture.messageId()))).isZero();
        String content = jdbcTemplate.queryForObject("SELECT content_masked FROM crm_notification "
                + "WHERE tenant_id=1 AND source_type='COMPLAINT_HIT' AND source_id=(SELECT id FROM crm_complaint_hit "
                + "WHERE tenant_id=1 AND source_type='WECOM_MESSAGE' AND source_id=?)", String.class,
                Long.toString(fixture.messageId()));
        assertThat(content).contains("138****8000").doesNotContain("13800138000");

        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,summary,created_by) "
                + "VALUES (1,?,2,'PHONE',?,2)", customerId, "人工跟进：" + keyword);
        long followupId = queryLong("SELECT id FROM crm_followup WHERE tenant_id=1 AND customer_id=? "
                + "ORDER BY id DESC LIMIT 1", customerId);
        String followupEventId = "complaint-followup-" + suffix;
        Map<String, Object> followupPayload = Map.of("followupId", followupId, "customerId", customerId,
                "sourceVersion", 0, "staffUserId", 2, "contactAt", Instant.now().toString());
        String followupEvent = envelope(followupEventId, ComplaintEventConsumer.FOLLOWUP_EVENT, "followup",
                followupId, followupPayload);
        kafkaTemplate.send(OutboxKafkaConfiguration.FOLLOWUP_EVENTS_TOPIC, followupEventId, followupEvent)
                .get(5, TimeUnit.SECONDS);
        kafkaTemplate.send(OutboxKafkaConfiguration.FOLLOWUP_EVENTS_TOPIC, followupEventId, followupEvent)
                .get(5, TimeUnit.SECONDS);
        awaitCount("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 AND source_type='FOLLOWUP' "
                + "AND source_id=?", 1, Long.toString(followupId));
        assertThat(inboxCount(ComplaintEventConsumer.FOLLOWUP_GROUP, followupEventId)).isEqualTo(1);

        String invalidEventId = "complaint-invalid-" + suffix;
        Map<String, Object> invalidPayload = new LinkedHashMap<>(fixture.payload());
        invalidPayload.put("text", "禁止信任事件正文");
        String invalid = envelope(invalidEventId, ComplaintEventConsumer.WECOM_EVENT, "wecom", fixture.messageId(),
                invalidPayload);
        kafkaTemplate.send(OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, invalidEventId, invalid)
                .get(5, TimeUnit.SECONDS);
        ConsumerRecord<String, String> dlq = awaitDlq(invalidEventId);
        assertThat(dlq.value()).isEqualTo(invalid);
        assertThat(new String(dlq.headers().lastHeader("kafka_dlt-original-topic").value(), StandardCharsets.UTF_8))
                .isEqualTo(OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC);
        assertThat(inboxCount(ComplaintEventConsumer.WECOM_GROUP, invalidEventId)).isZero();
    }

    private WecomFixture newWecom(String suffix, long customerId, String text) throws Exception {
        String corpId = "broker-corp-" + suffix;
        String externalId = "broker-external-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,link_type,link_reason,created_by) VALUES (1,?,?,2,?,"
                + "'GRANTED','synthetic-consent','MANUAL','合成测试映射',1)", corpId, externalId, customerId);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                + "VALUES (1,?,2,1,'MOCK','synthetic-member')", corpId);
        String objectKey = "synthetic/complaint-broker/" + suffix + ".json.enc";
        byte[] encrypted = cipher.encrypt(objectMapper.writeValueAsString(Map.of("messageType", "TEXT", "text", text)))
                .getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encrypted));
        jdbcTemplate.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,external_userid,"
                        + "staff_user_id,conversation_id,message_type,sender_type,sent_at,content_object_key,content_sha256,"
                        + "consent_snapshot,consent_evidence_ref,status) VALUES (1,?,?,1001,?,?,2,'broker-conversation-" + suffix + "',"
                        + "'TEXT','CUSTOMER',CURRENT_TIMESTAMP(3),?,?,'GRANTED','synthetic-consent','STORED')",
                corpId, "broker-msg-" + suffix, customerId, externalId, objectKey, hash);
        long messageId = queryLong("SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=?", corpId);
        LocalDateTime sentAt = jdbcTemplate.queryForObject("SELECT sent_at FROM crm_wecom_message WHERE tenant_id=1 AND id=?",
                LocalDateTime.class, messageId);
        when(objectStorage.getObject(objectKey)).thenAnswer(invocation -> new ByteArrayInputStream(encrypted));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageRef", messageId);
        payload.put("corpId", corpId);
        payload.put("msgId", "broker-msg-" + suffix);
        payload.put("archiveSeq", 1001);
        payload.put("conversationId", "broker-conversation-" + suffix);
        payload.put("customerId", customerId);
        payload.put("contentObjectKey", objectKey);
        payload.put("sentAt", sentAt.toInstant(ZoneOffset.UTC).toString());
        return new WecomFixture(messageId, payload);
    }

    private long newCustomer(String customerNo) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'合成客诉客户')", customerNo);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?", customerNo);
    }

    private String envelope(String eventId, String eventType, String aggregateType, long aggregateId,
                            Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", eventType);
        envelope.put("event_version", 1);
        envelope.put("tenant_id", 1);
        envelope.put("aggregate_type", aggregateType);
        envelope.put("aggregate_id", Long.toString(aggregateId));
        envelope.put("occurred_at", Instant.now().toString());
        envelope.put("correlation_id", eventId);
        envelope.put("payload", payload);
        return objectMapper.writeValueAsString(envelope);
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
        throw new AssertionError("客诉 Kafka Listener 分区分配未完成");
    }

    private void awaitCount(String sql, long expected, Object... args) throws InterruptedException {
        long actual = -1;
        for (int attempt = 0; attempt < 300; attempt++) {
            actual = queryLong(sql, args);
            if (actual == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private long inboxCount(String group, String eventId) {
        return queryLong("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                group, eventId);
    }

    private ConsumerRecord<String, String> awaitDlq(String key) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        properties.put("group.id", "complaint-dlq-inspector-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(ComplaintKafkaConfiguration.DLQ_TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("非法客诉事件未进入真实 DLQ: " + key);
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private record WecomFixture(long messageId, Map<String, Object> payload) {
    }
}
