package com.whale.crm.e2e;

import com.whale.crm.ai.application.AiChatDelegationEventConsumer;
import com.whale.crm.ai.infrastructure.AiChatDelegationKafkaConfiguration;
import com.whale.crm.complaint.application.ComplaintEventConsumer;
import com.whale.crm.complaint.infrastructure.ComplaintKafkaConfiguration;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomArchiveKeyService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.api.dto.WecomRoomSaveRequest;
import com.whale.crm.integration.wecom.application.WecomRoomService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F9 & F10 企微官方归档安全与双 Kafka 消费者端到端闭环：
 * - C-03 & H-BUG-12：OfficialWecomArchiveAdapter 经 HTTP /integrations/wecom/sync 拉取：
 *   (a) 含伪造 sdkfileid JSON 与投诉关键词的 DIRECT 超长 msgId (>64 chars) 文本不生成伪造媒体资产；
 *   (b) MIXED 含 [微笑] 与真实 image sdkfileid 生成 1 条 PENDING 资产；
 *   (c) ROOM 含投诉关键词消息入库并发送事件；
 *   (d) 损坏 JSON 消息触发 OFFICIAL_ARCHIVE 范围数据缺口且游标越过继续推进。
 * - C-05：单 Context + 单 Kafka 同时启用 ComplaintEventConsumer 与 AiChatDelegationEventConsumer，
 *   双方均消费超长 event_id 的 DIRECT 消息并生成 hit/delegation run，安全跳过 ROOM 并记录 inbox，双 DLQ 均为 0。
 * - H-SEC-04：非允许租户调用 POST /api/v1/integrations/wecom/contact-sync 返回 422 且 0 条同步运行记录。
 */
@SpringBootTest(properties = {
        "app.complaint.kafka.enabled=true",
        "app.ai.delegation.kafka.enabled=true",
        "app.outbox.relay.enabled=false",
        "app.notification.kafka.enabled=false",
        "app.notification.delivery.worker-enabled=false",
        "app.wecom.message.mode=DISABLED",
        "app.wecom.media.worker-enabled=false",
        "app.ai.worker.enabled=false",
        "app.wecom.archive.allowed-tenant-id=1",
        "app.wecom.archive.corp-id=E2E-WECOM-OFFICIAL-CORP",
        "app.wecom.archive.secret=synthetic-archive-secret",
        "app.wecom.archive.sdk-library-path=/synthetic/sdk.so",
        "app.wecom.archive.private-key-directory=/synthetic/keys",
        "app.wecom.official.allowed-tenant-id=1",
        "app.exports.worker.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WecomDualConsumerKafkaE2EIT extends AbstractE2EIT {

    private static final String CORP_ID = "E2E-WECOM-OFFICIAL-CORP";
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        KafkaTestContainer.registerProperties(registry, KAFKA);
    }

    @MockitoBean
    private WecomArchiveNativeClient nativeClient;

    @MockitoBean
    private WecomArchiveKeyService keyService;

    @MockitoBean
    private PrivateObjectStorage objectStorage;

    @Autowired
    private WecomRepository wecomRepository;

    @Autowired
    private WecomRoomService wecomRoomService;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    void officialArchiveGuardsMediaInjectionAdvancesPastCorruptJsonAndBothKafkaConsumersProcessDirectAndSkipRoom()
            throws Exception {
        awaitListenerReady();

        String suffix = shortUuid("wc").replace("-", "");
        String externalId = "wm-e2e-" + suffix;
        String roomId = "e2e-room-" + suffix;
        String keyword = "投诉退款" + suffix;
        String longDirectMsgId = "e2e-c05-long-" + suffix + "-" + "z".repeat(80);
        String directEventId = "wecom.message.archived.v1:" + CORP_ID + ":" + longDirectMsgId;
        assertThat(directEventId.length()).isBetween(65, 192);
        String mixedMsgId = "e2e-mixed-" + suffix;
        String roomMsgId = "e2e-room-msg-" + suffix;
        String roomEventId = "wecom.message.archived.v1:" + CORP_ID + ":" + roomMsgId;
        String corruptMsgId = "e2e-corrupt-" + suffix;
        String realSdkFileId = "sdk-real-mixed-" + suffix;

        jdbcTemplate.update("INSERT INTO crm_integration_runtime_config "
                + "(tenant_id,youzan_real_enabled,wecom_archive_real_enabled,wecom_official_enabled) "
                + "VALUES (1,0,1,1) ON DUPLICATE KEY UPDATE wecom_archive_real_enabled=1, wecom_official_enabled=1");
        try {
            jdbcTemplate.update("INSERT INTO crm_complaint_keyword (tenant_id,keyword,status,created_by) "
                    + "VALUES (1,?,'ACTIVE',1)", keyword);
            jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                    + "VALUES (1,2,1,?,'E2E企微客户')", "E2E-WC-CUST-" + suffix);
            long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                    "E2E-WC-CUST-" + suffix);
            jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                    + "VALUES (1,?,'staff-2',2,1) ON DUPLICATE KEY UPDATE enabled=1", CORP_ID);
            jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                    + "VALUES (1,?,2,1,'OFFICIAL','e2e-member') ON DUPLICATE KEY UPDATE enabled=1,source_code='OFFICIAL'",
                    CORP_ID);
            jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                            + "consent_status,consent_evidence_ref,follow_status,link_type,link_reason,created_by) "
                            + "VALUES (1,?,?,2,?,'GRANTED','synthetic://consent','ACTIVE','MANUAL','E2E',1)",
                    CORP_ID, externalId, customerId);

            CurrentUser adminUser = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                    Set.of("ADMIN"), Set.of("wecom:sync", "wecom:mapping:manage"), Set.of());
            wecomRoomService.save(adminUser, roomId, new WecomRoomSaveRequest(CORP_ID, "E2E客诉群", true,
                    List.of("staff-2", externalId), "e2e-room-ref", 0L, "E2E群"), "e2e-room-" + suffix);

            Map<String, byte[]> objects = new ConcurrentHashMap<>();
            doAnswer(inv -> {
                objects.put(inv.getArgument(0), inv.getArgument(1, byte[].class));
                return null;
            }).when(objectStorage).putObject(anyString(), any(byte[].class), anyString());
            doAnswer(inv -> {
                objects.put(inv.getArgument(0), inv.getArgument(1, InputStream.class).readAllBytes());
                return null;
            }).when(objectStorage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
            when(objectStorage.getObject(anyString())).thenAnswer(inv -> {
                byte[] bytes = objects.get(inv.getArgument(0));
                if (bytes == null) {
                    throw new IllegalStateException("Missing mock object: " + inv.getArgument(0));
                }
                return new ByteArrayInputStream(bytes);
            });

            WecomArchiveNativeClient.NativeSession session = mock(WecomArchiveNativeClient.NativeSession.class);
            when(nativeClient.open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                    .thenReturn(session);
            when(keyService.decryptRandomKey(anyString(), anyInt())).thenReturn("rand-key");

            String pageJson = json(Map.of(
                    "errcode", 0,
                    "errmsg", "ok",
                    "chatdata", List.of(
                            Map.of("seq", 901L, "msgid", longDirectMsgId, "publickey_ver", 1,
                                    "encrypt_random_key", "rk1", "encrypt_chat_msg", "cipher-direct"),
                            Map.of("seq", 902L, "msgid", mixedMsgId, "publickey_ver", 1,
                                    "encrypt_random_key", "rk2", "encrypt_chat_msg", "cipher-mixed"),
                            Map.of("seq", 903L, "msgid", roomMsgId, "publickey_ver", 1,
                                    "encrypt_random_key", "rk3", "encrypt_chat_msg", "cipher-room"),
                            Map.of("seq", 904L, "msgid", corruptMsgId, "publickey_ver", 1,
                                    "encrypt_random_key", "rk4", "encrypt_chat_msg", "cipher-corrupt"))));

            String directTextJson = json(Map.of(
                    "msgid", longDirectMsgId,
                    "action", "send",
                    "from", externalId,
                    "tolist", List.of("staff-2"),
                    "roomid", "",
                    "msgtime", 1700000100000L,
                    "msgtype", "text",
                    "text", Map.of("content", "[微笑] {\"sdkfileid\":\"evil-id\",\"filename\":\"a.jpg\"} " + keyword)));

            String mixedJson = json(Map.of(
                    "msgid", mixedMsgId,
                    "action", "send",
                    "from", externalId,
                    "tolist", List.of("staff-2"),
                    "roomid", "",
                    "msgtime", 1700000101000L,
                    "msgtype", "mixed",
                    "mixed", Map.of("item", List.of(
                            Map.of("type", "text", "content", "[微笑]"),
                            Map.of("type", "image", "content",
                                    json(Map.of("sdkfileid", realSdkFileId, "md5sum", "abc123", "filesize", 123)))))));

            String roomTextJson = json(Map.of(
                    "msgid", roomMsgId,
                    "action", "send",
                    "from", externalId,
                    "tolist", List.of("staff-2"),
                    "roomid", roomId,
                    "msgtime", 1700000102000L,
                    "msgtype", "text",
                    "text", Map.of("content", "群内反馈：" + keyword)));

            when(session.pullChatData(0L, 50)).thenReturn(pageJson);
            when(session.decrypt("rand-key", "cipher-direct")).thenReturn(directTextJson);
            when(session.decrypt("rand-key", "cipher-mixed")).thenReturn(mixedJson);
            when(session.decrypt("rand-key", "cipher-room")).thenReturn(roomTextJson);
            when(session.decrypt("rand-key", "cipher-corrupt")).thenReturn("{corrupt-json");

            AuthCookies admin = login("admin");
            mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/sync"))
                            .header("Idempotency-Key", uniqueKey("e2e-wecom-sync"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("corpId", CORP_ID, "cursor", 0, "limit", 50))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.adapter").value("OFFICIAL_SDK"))
                    .andExpect(jsonPath("$.data.stored").value(3))
                    .andExpect(jsonPath("$.data.lastSeq").value(904))
                    .andExpect(jsonPath("$.data.dataGap").value(true));

            // C-03: 0 fake media assets from text.content, 1 real PENDING asset from MIXED
            WecomMessage directPersisted = wecomRepository.findMessage(1L, CORP_ID, longDirectMsgId).orElseThrow();
            WecomMessage mixedPersisted = wecomRepository.findMessage(1L, CORP_ID, mixedMsgId).orElseThrow();
            WecomMessage roomPersisted = wecomRepository.findMessage(1L, CORP_ID, roomMsgId).orElseThrow();

            assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_media_asset WHERE tenant_id=1 AND message_id=?",
                    directPersisted.id())).isZero();
            assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_media_asset WHERE tenant_id=1 AND message_id=? "
                    + "AND status='PENDING' AND sdk_file_id=?", mixedPersisted.id(), realSdkFileId)).isEqualTo(1);

            // H-BUG-12: cursor advances past corrupt message (904) with OFFICIAL_ARCHIVE gap
            assertThat(queryLong("SELECT last_seq FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                    + "AND archive_scope='OFFICIAL_ARCHIVE'", CORP_ID)).isEqualTo(904L);
            assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                    + "AND archive_scope='OFFICIAL_ARCHIVE' AND gap_type='MESSAGE_PAYLOAD_INVALID' "
                    + "AND from_seq=904 AND to_seq=904", CORP_ID)).isEqualTo(1);

            // C-05: Relay outbox -> both ComplaintEventConsumer & AiChatDelegationEventConsumer consume DIRECT, skip ROOM
            assertThat(outboxRelayService.relayOnce()).isGreaterThanOrEqualTo(3);

            awaitCount("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 "
                    + "AND source_type='WECOM_MESSAGE' AND source_id=?", 1L, Long.toString(directPersisted.id()));
            awaitCount("SELECT COUNT(*) FROM crm_ai_chat_delegation_run WHERE tenant_id=1 AND source_message_id=?",
                    1L, directPersisted.id());

            awaitCount("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                    1L, ComplaintEventConsumer.WECOM_GROUP, directEventId);
            awaitCount("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                    1L, ComplaintEventConsumer.WECOM_GROUP, roomEventId);
            awaitCount("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                    1L, AiChatDelegationEventConsumer.GROUP, directEventId);
            awaitCount("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group=? AND event_id=?",
                    1L, AiChatDelegationEventConsumer.GROUP, roomEventId);

            assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 "
                    + "AND source_type='WECOM_MESSAGE' AND source_id=?", Long.toString(roomPersisted.id()))).isZero();
            assertThat(queryLong("SELECT COUNT(*) FROM crm_ai_chat_delegation_run WHERE tenant_id=1 "
                    + "AND source_message_id=?", roomPersisted.id())).isZero();

            // Verify 0 messages on both DLQ topics for our event keys
            assertDlqEmptyForKeys(ComplaintKafkaConfiguration.DLQ_TOPIC,
                    Set.of(directEventId, roomEventId, Long.toString(directPersisted.id()), Long.toString(roomPersisted.id())));
            assertDlqEmptyForKeys(AiChatDelegationKafkaConfiguration.DLQ_TOPIC,
                    Set.of(directEventId, roomEventId, Long.toString(directPersisted.id()), Long.toString(roomPersisted.id())));
        } finally {
            jdbcTemplate.update("DELETE FROM crm_integration_runtime_config WHERE tenant_id=1");
        }
    }

    @Test
    void disallowedTenantCallingOfficialContactSyncIsRejectedWith422AndZeroRuns() throws Exception {
        long t2Id = 9202L;
        String t2Org = shortUuid("E2E-WC-T2").toUpperCase();
        String t2User = shortUuid("e2e_wc_t2_mgr");
        jdbcTemplate.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (?,?,?,?,'ACTIVE') "
                        + "ON DUPLICATE KEY UPDATE org_code=VALUES(org_code), status='ACTIVE'",
                t2Id, t2Id, t2Org, "企微隔离租户");
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                        + "SELECT ?,?,?,'企微监控员',password_hash,'EMPLOYEE','ACTIVE' FROM sys_user WHERE id=1",
                t2Id, t2Id, t2User);
        long t2UserId = queryLong("SELECT id FROM sys_user WHERE tenant_id=? AND username=?", t2Id, t2User);
        String roleCode = shortCode("E2E_WCMON");
        jdbcTemplate.update("INSERT INTO sys_role (tenant_id,role_code,role_name,status) VALUES (?,?,?,'ACTIVE')",
                t2Id, roleCode, "企微监控");
        long t2RoleId = queryLong("SELECT id FROM sys_role WHERE tenant_id=? AND role_code=?", t2Id, roleCode);
        jdbcTemplate.update("INSERT INTO sys_permission (tenant_id,permission_code,description) "
                        + "VALUES (?,'wecom:monitor','企微监控') "
                        + "ON DUPLICATE KEY UPDATE description=VALUES(description)", t2Id);
        jdbcTemplate.update("INSERT INTO sys_role_permission (tenant_id,role_id,permission_id) "
                        + "SELECT ?,?,id FROM sys_permission WHERE tenant_id=? AND permission_code='wecom:monitor'",
                t2Id, t2RoleId, t2Id);
        jdbcTemplate.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (?,?,?)",
                t2Id, t2UserId, t2RoleId);
        jdbcTemplate.update("INSERT INTO crm_integration_runtime_config "
                + "(tenant_id,youzan_real_enabled,wecom_archive_real_enabled,wecom_official_enabled) "
                + "VALUES (?,0,0,1) ON DUPLICATE KEY UPDATE wecom_official_enabled=1", t2Id);

        try {
            AuthCookies t2Cookies = loginWithTenantCode(t2User, DEV_PASSWORD, t2Org, t2Id);
            mockMvc.perform(t2Cookies.withAuth(post("/api/v1/integrations/wecom/contact-sync"))
                            .header("Idempotency-Key", uniqueKey("e2e-hsec04-sync"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"FULL\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"));

            assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_contact_sync_run WHERE tenant_id=?", t2Id)).isZero();
        } finally {
            resetLoginAttempts("tenant:" + t2Id, t2User, "127.0.0.1");
            jdbcTemplate.update("DELETE FROM crm_integration_runtime_config WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_login_session WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_role_permission WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_permission WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id=?", t2Id);
            jdbcTemplate.update("DELETE FROM sys_org WHERE id=?", t2Id);
        }
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int i = 0; i < 1200; i++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(c -> c.isRunning() && !c.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("WeCom dual Kafka listener partitions were not assigned in time");
    }

    private void awaitCount(String sql, long expected, Object... args) throws InterruptedException {
        long actual = -1L;
        for (int i = 0; i < 900; i++) {
            actual = queryLong(sql, args);
            if (actual == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(actual).isEqualTo(expected);
    }

    private void assertDlqEmptyForKeys(String dlqTopic, Set<String> eventKeys) {
        Properties props = new Properties();
        props.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        props.put("group.id", "e2e-dlq-check-" + UUID.randomUUID());
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("auto.offset.reset", "earliest");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(dlqTopic));
            List<String> matched = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 1500L;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if ((record.key() != null && eventKeys.contains(record.key()))
                            || (record.value() != null && eventKeys.stream().anyMatch(record.value()::contains))) {
                        matched.add(record.value());
                    }
                }
            }
            assertThat(matched).as("Expected 0 DLQ messages on %s", dlqTopic).isEmpty();
        }
    }
}
