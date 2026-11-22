package com.whale.crm.ai;

import com.whale.crm.ai.application.AiChatDelegationEventConsumer;
import com.whale.crm.ai.infrastructure.AiChatDelegationKafkaConfiguration;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomAdapter;
import com.whale.crm.integration.wecom.adapter.WecomAdapterRouter;
import com.whale.crm.integration.wecom.adapter.WecomArchiveMessage;
import com.whale.crm.integration.wecom.adapter.WecomArchivePage;
import com.whale.crm.integration.wecom.api.dto.WecomRoomSaveRequest;
import com.whale.crm.integration.wecom.api.dto.WecomSyncRequest;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.security.CurrentUser;
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

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.ai.delegation.kafka.enabled=true", "app.outbox.relay.enabled=false",
        "app.complaint.kafka.enabled=false", "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false", "app.wecom.message.mode=DISABLED",
        "app.ai.worker.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AiChatDelegationBrokerReliabilityIT extends AbstractMysqlIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry, KAFKA);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired private WecomArchiveService wecomArchiveService;
    @Autowired private WecomRoomService wecomRoomService;
    @Autowired private WecomRepository wecomRepository;
    @Autowired private OutboxRelayService outboxRelayService;
    @MockitoBean private PrivateObjectStorage objectStorage;
    @MockitoBean private WecomAdapterRouter wecomAdapterRouter;

    @Test
    void realWecomArchiveServiceDirectLongEventIdAndRoomEventProcessWithoutDlqAndInvalidGoesToDlq() throws Exception {
        awaitListenerReady();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String corpId = "c05-ai-" + suffix;
        String externalId = "wm-ai-" + suffix;
        String roomId = "c05-ai-room-" + suffix;

        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'合成托管客户')", "IT-C05-AI-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "IT-C05-AI-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',2,1)", corpId);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,link_type,link_reason,created_by) VALUES (1,?,?,2,?,"
                + "'GRANTED','synthetic-consent','MANUAL','合成测试',1)", corpId, externalId, customerId);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                + "VALUES (1,?,2,1,'MOCK','synthetic-member')", corpId);

        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("wecom:sync", "wecom:mapping:manage"), Set.of());
        wecomRoomService.save(admin, roomId, new WecomRoomSaveRequest(corpId, "C05托管群", true,
                List.of("staff-2", externalId), "synthetic-room-ref", 0L, "C05测试群"), "c05-ai-room-" + suffix);

        Map<String, byte[]> objects = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            objects.put(inv.getArgument(0), inv.getArgument(1, byte[].class));
            return null;
        }).when(objectStorage).putObject(anyString(), any(byte[].class), anyString());
        when(objectStorage.getObject(anyString())).thenAnswer(inv -> new ByteArrayInputStream(objects.get(inv.getArgument(0))));

        String longMsgId = "c05-ai-long-" + suffix + "-" + "x".repeat(90);
        String directEventId = "wecom.message.archived.v1:" + corpId + ":" + longMsgId;
        assertThat(directEventId.length()).isGreaterThan(64).isLessThanOrEqualTo(192);
        String roomMsgId = "c05-ai-room-msg-" + suffix;
        String roomEventId = "wecom.message.archived.v1:" + corpId + ":" + roomMsgId;

        WecomAdapter adapter = mock(WecomAdapter.class);
        when(wecomAdapterRouter.adapter(1L)).thenReturn(adapter);
        when(adapter.name()).thenReturn("MOCK");
        when(adapter.pullContacts(corpId)).thenReturn(List.of());
        Instant now = Instant.now();
        when(adapter.pullArchive(corpId, 0L, 50)).thenReturn(new WecomArchivePage(List.of(
                new WecomArchiveMessage(longMsgId, 1L, externalId, 2L, "conv-" + suffix, "TEXT", "CUSTOMER",
                        now, "想了解预约"),
                WecomArchiveMessage.room(roomMsgId, 2L, roomId, "TEXT", "CUSTOMER", now.plusMillis(10),
                        "群消息", externalId, null, List.of(), null)
        ), 2L, false));

        wecomArchiveService.sync(admin, new WecomSyncRequest(corpId, 0L, 50));
        assertThat(outboxRelayService.relayOnce()).isGreaterThanOrEqualTo(2);

        WecomMessage directPersisted = wecomRepository.findMessage(1L, corpId, longMsgId).orElseThrow();
        WecomMessage roomPersisted = wecomRepository.findMessage(1L, corpId, roomMsgId).orElseThrow();

        awaitCount("SELECT COUNT(*) FROM crm_ai_chat_delegation_run WHERE tenant_id=1 AND source_message_id=?",
                1, directPersisted.id());
        awaitCount("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                1, AiChatDelegationEventConsumer.GROUP, roomEventId);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                AiChatDelegationEventConsumer.GROUP, directEventId)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_ai_chat_delegation_run WHERE tenant_id=1 AND source_message_id=?",
                roomPersisted.id())).isZero();

        String invalidEventId = "ai-invalid-" + suffix;
        Map<String, Object> badPayload = new LinkedHashMap<>();
        badPayload.put("messageRef", directPersisted.id());
        badPayload.put("corpId", corpId);
        badPayload.put("msgId", longMsgId);
        badPayload.put("archiveSeq", 1);
        badPayload.put("contentObjectKey", directPersisted.contentObjectKey());
        badPayload.put("sentAt", now.toString());
        badPayload.put("text", "forbidden");
        Map<String, Object> badEnvelope = new LinkedHashMap<>();
        badEnvelope.put("event_id", invalidEventId);
        badEnvelope.put("event_type", "wecom.message.archived.v1");
        badEnvelope.put("event_version", 1);
        badEnvelope.put("tenant_id", 1);
        badEnvelope.put("aggregate_type", "wecom");
        badEnvelope.put("aggregate_id", Long.toString(directPersisted.id()));
        badEnvelope.put("occurred_at", now.toString());
        badEnvelope.put("correlation_id", invalidEventId);
        badEnvelope.put("payload", badPayload);
        String invalidJson = objectMapper.writeValueAsString(badEnvelope);
        kafkaTemplate.send(OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, invalidEventId, invalidJson)
                .get(30, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dlq = awaitDlq(invalidEventId,
                Set.of(Long.toString(directPersisted.id()), Long.toString(roomPersisted.id())));
        assertThat(dlq.value()).isEqualTo(invalidJson);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                AiChatDelegationEventConsumer.GROUP, invalidEventId)).isZero();
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int attempt = 0; attempt < 1200; attempt++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(container -> container.isRunning() && !container.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("AI托管 Kafka Listener 分区分配未完成");
    }

    private void awaitCount(String sql, long expected, Object... args) throws InterruptedException {
        long actual = -1;
        for (int attempt = 0; attempt < 900; attempt++) {
            actual = queryLong(sql, args);
            if (actual == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private ConsumerRecord<String, String> awaitDlq(String targetKey, Set<String> forbiddenKeys) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        properties.put("group.id", "ai-delegation-dlq-inspector-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(AiChatDelegationKafkaConfiguration.DLQ_TOPIC));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    assertThat(forbiddenKeys).doesNotContain(record.key());
                    if (targetKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("非法AI托管事件未进入真实 DLQ: " + targetKey);
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
