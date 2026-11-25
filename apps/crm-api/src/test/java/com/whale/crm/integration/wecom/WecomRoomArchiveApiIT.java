package com.whale.crm.integration.wecom;

import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveKeyService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.wecom.archive.allowed-tenant-id=1", "app.wecom.archive.corp-id=ROOM-ARCHIVE-IT",
        "app.wecom.archive.secret=synthetic-secret", "app.wecom.archive.sdk-library-path=/synthetic/sdk.so",
        "app.wecom.archive.private-key-directory=/synthetic/keys", "app.outbox.relay.enabled=false",
        "app.wecom.media.worker-enabled=false",
})
class WecomRoomArchiveApiIT extends AbstractMysqlIT {
    private static final String CORP = "ROOM-ARCHIVE-IT";

    @MockitoBean
    private WecomArchiveNativeClient nativeClient;
    @MockitoBean
    private WecomArchiveKeyService keyService;
    @MockitoBean
    private PrivateObjectStorage storage;
    @MockitoBean
    private IntegrationRuntimeConfigService runtimeConfig;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private WecomRepository repository;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final Map<String, String> plaintext = new ConcurrentHashMap<>();
    private WecomArchiveNativeClient.NativeSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = org.mockito.Mockito.mock(WecomArchiveNativeClient.NativeSession.class);
        when(runtimeConfig.wecomArchiveRealEnabled(1L)).thenReturn(true);
        when(nativeClient.open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(session);
        when(keyService.decryptRandomKey(anyString(), anyInt())).thenReturn("synthetic-random-key");
        objects.clear();
        plaintext.clear();
        cleanupArchiveFixtures();
        doAnswer(invocation -> {
            objects.put(invocation.getArgument(0), invocation.getArgument(1, byte[].class));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(objects.get(invocation.getArgument(0))));
        when(session.decrypt(anyString(), anyString())).thenAnswer(invocation -> plaintext.get(invocation.getArgument(1)));
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',2,1),(1,?,'staff-3',3,1) ON DUPLICATE KEY UPDATE enabled=1", CORP, CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code) "
                + "VALUES (1,?,2,1,'OFFICIAL') ON DUPLICATE KEY UPDATE enabled=1,source_code='OFFICIAL'", CORP);
    }

    @AfterEach
    void tearDown() {
        cleanupArchiveFixtures();
    }

    private void cleanupArchiveFixtures() {
        jdbcTemplate.update("DELETE ma FROM crm_wecom_media_asset ma "
                + "JOIN crm_wecom_message m ON m.id=ma.message_id WHERE ma.tenant_id=1 AND m.corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_recall_event WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=?", CORP);
    }

    @Test
    void confirmedRoomStoresEncryptedMessageWithoutChoosingACustomer() throws Exception {
        String suffix = suffix();
        String room = "wr-room-" + suffix;
        String external = "wm-room-" + suffix;
        String msgId = "ROOM-MSG-" + suffix;
        grantContact(external);
        when(session.pullChatData(0, 100)).thenReturn(page(401, msgId, roomMessage(msgId, room, external, "群聊协议消息")));

        sync("unconfirmed-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.dataGap").value(true));
        assertThat(repository.findMessage(1L, CORP, msgId)).isEmpty();
        assertThat(objects).isEmpty();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='ROOM_CONTEXT_UNCONFIRMED' AND status='OPEN'", CORP)).isEqualTo(1);

        saveRoom(room, List.of("staff-2", external), 0, true, "create-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.archivable").value(true));
        sync("store-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1))
                .andExpect(jsonPath("$.data.dataGap").value(false));
        WecomMessage message = repository.findMessage(1L, CORP, msgId).orElseThrow();
        assertThat(message.room()).isTrue();
        assertThat(message.customerId()).isNull();
        assertThat(message.externalUserid()).isNull();
        assertThat(message.staffUserId()).isNull();
        assertThat(message.senderWecomUserid()).isEqualTo(external);
        assertThat(message.roomRosterVersion()).isZero();

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(manager.cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("群聊协议消息"));
        mockMvc.perform(get("/api/v1/integrations/wecom/messages").param("conversationId", room)
                        .cookie(manager.cookies())).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(login("manager2").cookies())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/integrations/wecom/rooms").param("corpId", CORP).cookie(manager.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].roomId").value(room));
        mockMvc.perform(get("/api/v1/integrations/wecom/rooms").param("corpId", CORP)
                        .cookie(login("manager2").cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(put("/api/v1/integrations/wecom/rooms/" + room).cookie(manager.cookies())
                .header("X-CSRF-Token", manager.csrf()).header("Idempotency-Key", "forbidden-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(roomBody(room,
                        List.of("staff-2", external), 0, true)))).andExpect(status().isForbidden());
    }

    @Test
    void rosterChangesAreAuditedAndRevokeOnlyNewIngestion() throws Exception {
        String suffix = suffix();
        String room = "wr-roster-" + suffix;
        String first = "wm-roster-a-" + suffix;
        String second = "wm-roster-b-" + suffix;
        String original = "ROOM-ORIGINAL-" + suffix;
        grantContact(first);
        grantContact(second);
        List<String> initial = List.of("staff-2", first, second);
        saveRoom(room, initial, 0, true, "initial-" + suffix).andExpect(status().isOk());
        when(session.pullChatData(0, 100)).thenReturn(page(411, original, roomMessage(original, room, second, "退群前消息")));
        sync("original-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1));
        WecomMessage before = repository.findMessage(1L, CORP, original).orElseThrow();

        saveRoom(room, List.of("staff-2", first), 0, true, "remove-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_room_participant WHERE tenant_id=1 "
                + "AND corp_id=? AND room_id=? AND wecom_userid=?", String.class, CORP, room, second)).isEqualTo("REMOVED");
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + before.id() + "/content")
                        .cookie(login("manager1").cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("退群前消息"));
        saveRoom(room, initial, 0, true, "stale-" + suffix).andExpect(status().isConflict());
        saveRoom(room, initial, 0, true, "initial-" + suffix).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_room_participant WHERE tenant_id=1 AND corp_id=? AND room_id=?",
                CORP, room)).isEqualTo(3);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND target_type='WECOM_ROOM' "
                + "AND target_id=?", room)).isEqualTo(2);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND target_type='WECOM_ROOM' "
                + "AND target_id=? AND action_code='WECOM_ROOM_UPDATE' AND before_json LIKE ? AND after_json LIKE ?",
                room, "%" + second + "%", "%REMOVED%")).isEqualTo(1);

        String after = "ROOM-AFTER-REMOVE-" + suffix;
        when(session.pullChatData(0, 100)).thenReturn(page(412, after, roomMessage(after, room, second, "退群后不得入库")));
        sync("after-remove-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.skipped").value(1));
        assertThat(repository.findMessage(1L, CORP, after)).isEmpty();
        saveRoom(room, List.of("staff-2", first), 1, false, "disable-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        String disabled = "ROOM-DISABLED-" + suffix;
        when(session.pullChatData(0, 100)).thenReturn(page(413, disabled, roomMessage(disabled, room, first, "停用后不得入库")));
        sync("disabled-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.skipped").value(1));
        assertThat(repository.findMessage(1L, CORP, disabled)).isEmpty();
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + before.id() + "/content")
                        .cookie(login("manager1").cookies())).andExpect(status().isOk());

        AuthCookies admin = login("admin");
        mockMvc.perform(put("/api/v1/integrations/wecom/rooms/" + room).cookie(admin.cookies())
                .header("Idempotency-Key", "no-csrf-" + suffix).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(roomBody(room, List.of("staff-2", first), 3, true))))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_room WHERE tenant_id=1 AND corp_id=? "
                + "AND room_id=?", String.class, CORP, room)).isEqualTo("DISABLED");
    }

    @Test
    void roomRecallAndMediaReusePendingCompletionAndPrivateReads() throws Exception {
        String suffix = suffix();
        String room = "wr-recall-" + suffix;
        String external = "wm-recall-room-" + suffix;
        String original = "ROOM-RECALL-ORIGINAL-" + suffix;
        String recall = "ROOM-RECALL-EVENT-" + suffix;
        grantContact(external);
        saveRoom(room, List.of("staff-2", external), 0, true, "setup-" + suffix).andExpect(status().isOk());
        String recallContent = "{\"msgid\":\"" + recall + "\",\"action\":\"recall\",\"from\":\"" + external
                + "\",\"tolist\":[],\"roomid\":\"" + room + "\",\"msgtime\":1700000001000,\"msgtype\":\"revoke\","
                + "\"revoke\":{\"pre_msgid\":\"" + original + "\"}}";
        String originalContent = "{\"msgid\":\"" + original + "\",\"action\":\"send\",\"from\":\"" + external
                + "\",\"tolist\":[],\"roomid\":\"" + room + "\",\"msgtime\":1700000000000,\"msgtype\":\"file\","
                + "\"file\":{\"filename\":\"synthetic.txt\",\"sdkfileid\":\"sdk-room-" + suffix + "\"}}";
        when(session.pullChatData(0, 100)).thenReturn(page(421, recall, recallContent), page(422, original, originalContent));
        sync("recall-first-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.recalled").value(1))
                .andExpect(jsonPath("$.data.dataGap").value(true));
        sync("original-later-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1))
                .andExpect(jsonPath("$.data.dataGap").value(false));
        WecomMessage message = repository.findMessage(1L, CORP, original).orElseThrow();
        assertThat(message.recalledAt()).isNotNull();
        assertThat(message.recallMessageId()).isEqualTo(recall);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_media_asset WHERE tenant_id=1 AND message_id=?",
                message.id())).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_recall_event WHERE tenant_id=1 AND corp_id=? "
                + "AND recall_message_id=? AND conversation_type='ROOM' AND external_userid IS NULL AND staff_user_id IS NULL",
                CORP, recall)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(login("manager1").cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("synthetic.txt"))
                .andExpect(jsonPath("$.data.recalledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.mediaAssets[0].status").value("PENDING"));
    }

    @Test
    void consentMustCoverEveryCurrentExternalParticipant() throws Exception {
        String suffix = suffix();
        String room = "wr-consent-" + suffix;
        String external = "wm-consent-" + suffix;
        grantContact(external);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,"
                + "consent_status,consent_evidence_ref,follow_status) VALUES (1,?,'wm-pending-" + suffix
                + "',2,'PENDING','synthetic://pending','ACTIVE')", CORP);
        saveRoom(room, List.of("staff-2", external, "wm-pending-" + suffix), 0, true, "pending-" + suffix)
                .andExpect(status().isBadRequest());
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_room WHERE tenant_id=1 AND corp_id=? AND room_id=?",
                CORP, room)).isZero();
        saveRoom(room, List.of("staff-2", external), 0, true, "granted-" + suffix).andExpect(status().isOk());
        String msgId = "ROOM-CONSENT-" + suffix;
        when(session.pullChatData(0, 100)).thenReturn(page(431, msgId, roomMessage(msgId, room, external, "同意状态测试")));
        sync("consent-" + suffix).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1));
        WecomMessage message = repository.findMessage(1L, CORP, msgId).orElseThrow();
        jdbcTemplate.update("UPDATE crm_wecom_contact SET consent_status='REVOKED',version=version+1 "
                + "WHERE tenant_id=1 AND corp_id=? AND external_userid=? AND staff_user_id=2", CORP, external);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(login("manager1").cookies())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/integrations/wecom/messages").param("conversationId", room)
                        .cookie(login("manager1").cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void removedExternalRevocationDoesNotBlockRoomWhileActiveExternalRevocationStillBlocks() throws Exception {
        String suffix = suffix();
        String room = "wr-removed-consent-" + suffix;
        String activeExternal = "wm-active-ext-" + suffix;
        String removedExternal = "wm-removed-ext-" + suffix;
        String msgId = "ROOM-REMOVED-CONSENT-" + suffix;
        grantContact(activeExternal);
        grantContact(removedExternal);
        saveRoom(room, List.of("staff-2", activeExternal, removedExternal), 0, true, "init-" + suffix)
                .andExpect(status().isOk());
        when(session.pullChatData(0, 100)).thenReturn(page(441, msgId,
                roomMessage(msgId, room, activeExternal, "退群成员撤回不影响在群消息可读")));
        sync("removed-consent-init-" + suffix).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stored").value(1));
        WecomMessage message = repository.findMessage(1L, CORP, msgId).orElseThrow();

        // Remove removedExternal from room roster (status becomes REMOVED), then revoke its consent
        saveRoom(room, List.of("staff-2", activeExternal), 0, true, "remove-ext-" + suffix)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_room_participant WHERE tenant_id=1 "
                + "AND corp_id=? AND room_id=? AND wecom_userid=?", String.class, CORP, room, removedExternal))
                .isEqualTo("REMOVED");
        jdbcTemplate.update("UPDATE crm_wecom_contact SET consent_status='REVOKED',version=version+1 "
                + "WHERE tenant_id=1 AND corp_id=? AND external_userid=? AND staff_user_id=2", CORP, removedExternal);

        // Room messages must remain readable because only ACTIVE external participants determine current room consent
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(manager.cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("退群成员撤回不影响在群消息可读"));
        mockMvc.perform(get("/api/v1/integrations/wecom/messages").param("conversationId", room)
                        .cookie(manager.cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].currentConsentStatus").value("GRANTED"));
        assertThat(repository.findMessage(1L, CORP, msgId).orElseThrow().currentConsentStatus())
                .isEqualTo("GRANTED");

        // Revoking an ACTIVE external participant's consent must still block the room immediately
        jdbcTemplate.update("UPDATE crm_wecom_contact SET consent_status='REVOKED',version=version+1 "
                + "WHERE tenant_id=1 AND corp_id=? AND external_userid=? AND staff_user_id=2", CORP, activeExternal);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/" + message.id() + "/content")
                        .cookie(manager.cookies())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/integrations/wecom/messages").param("conversationId", room)
                        .cookie(manager.cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        assertThat(repository.findMessage(1L, CORP, msgId).orElseThrow().currentConsentStatus())
                .isEqualTo("REVOKED");
    }

    private void grantContact(String external) {
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,"
                + "consent_status,consent_evidence_ref,follow_status) VALUES (1,?,?,2,'GRANTED','synthetic://consent','ACTIVE')",
                CORP, external);
    }

    private String roomMessage(String msgId, String room, String external, String text) {
        String value = "{\"msgid\":\"" + msgId + "\",\"action\":\"send\",\"from\":\"" + external
                + "\",\"tolist\":[],\"roomid\":\"" + room + "\",\"msgtime\":1700000000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + text + "\"}}";
        plaintext.put(msgId, value);
        return value;
    }

    private String page(long seq, String msgId, String content) throws Exception {
        plaintext.put(msgId, content);
        return objectMapper.writeValueAsString(Map.of("errcode", 0, "chatdata", List.of(Map.of("seq", seq,
                "msgid", msgId, "publickey_ver", 3, "encrypt_random_key", "random-" + msgId,
                "encrypt_chat_msg", msgId))));
    }

    private ResultActions sync(String key) throws Exception {
        AuthCookies admin = login("admin");
        return mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/sync"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("corpId", CORP, "cursor", 0, "limit", 100))));
    }

    private ResultActions saveRoom(String room, List<String> members, long version, boolean enabled,
                                   String key) throws Exception {
        AuthCookies admin = login("admin");
        return mockMvc.perform(admin.withAuth(put("/api/v1/integrations/wecom/rooms/" + room))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(roomBody(room, members, version, enabled))));
    }

    private Map<String, Object> roomBody(String room, List<String> members, long version, boolean enabled) {
        return Map.of("corpId", CORP, "displayName", "协议测试群", "enabled", enabled,
                "memberUserids", members, "sourceEvidenceRef", "synthetic://room-roster",
                "version", version, "reason", "核对完整群成员清单");
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
