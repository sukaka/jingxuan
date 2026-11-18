package com.whale.crm.integration.wecom;

import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveKeyService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.wecom.archive.allowed-tenant-id=1", "app.wecom.archive.corp-id=OFFICIAL-ARCHIVE-IT",
        "app.wecom.archive.secret=synthetic-secret", "app.wecom.archive.sdk-library-path=/synthetic/sdk.so",
        "app.wecom.archive.private-key-directory=/synthetic/keys", "app.outbox.relay.enabled=false",
        "app.wecom.media.worker-enabled=true",
})
class WecomOfficialArchiveRoutingApiIT extends AbstractMysqlIT {
    private static final String CORP = "OFFICIAL-ARCHIVE-IT";

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
    @Autowired
    private WecomArchiveService archiveService;
    @Autowired
    private com.whale.crm.integration.wecom.media.application.WecomMediaWorker mediaWorker;
    @Autowired
    private com.whale.crm.integration.wecom.media.domain.WecomMediaRepository mediaRepository;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private WecomArchiveNativeClient.NativeSession session;

    @BeforeEach
    void setUp() {
        session = org.mockito.Mockito.mock(WecomArchiveNativeClient.NativeSession.class);
        when(runtimeConfig.wecomArchiveRealEnabled(1L)).thenReturn(true);
        when(nativeClient.open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(session);
        when(keyService.decryptRandomKey(anyString(), anyInt())).thenReturn("synthetic-random-key");
        jdbcTemplate.update("DELETE FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=?", CORP);
        objects.clear();
        doAnswer(invocation -> {
            objects.put(invocation.getArgument(0), invocation.getArgument(1, byte[].class));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString())).thenAnswer(invocation -> new ByteArrayInputStream(objects.get(invocation.getArgument(0))));
    }

    @Test
    void officialModeUsesConsentEncryptedStorageOutboxAndIndependentCursor() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String external = "wm-archive-" + suffix;
        String msgId = "OFFICIAL-MSG-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'官方归档测试客户')", "OFFICIAL-CUSTOMER-" + suffix);
        long customerId = jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                Long.class, "OFFICIAL-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',2,1) ON DUPLICATE KEY UPDATE enabled=1", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code) "
                + "VALUES (1,?,2,1,'OFFICIAL') ON DUPLICATE KEY UPDATE enabled=1,source_code='OFFICIAL'", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,follow_status) VALUES (1,?,?,2,?,'GRANTED','synthetic://consent','ACTIVE')",
                CORP, external, customerId);
        String plaintext = "{\"msgid\":\"" + msgId + "\",\"action\":\"send\",\"from\":\"" + external + "\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"官方协议测试消息\"}}";
        String encryptedPage = "{\"errcode\":0,\"errmsg\":\"ok\",\"chatdata\":[{"
                + "\"seq\":201,\"msgid\":\"" + msgId + "\",\"publickey_ver\":3,"
                + "\"encrypt_random_key\":\"synthetic-random-key\",\"encrypt_chat_msg\":\"synthetic-cipher\"}]}";
        when(session.pullChatData(0, 100)).thenReturn(encryptedPage);
        when(session.decrypt("synthetic-random-key", "synthetic-cipher")).thenReturn(plaintext);

        sync(0)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.adapter").value("OFFICIAL_SDK"))
                .andExpect(jsonPath("$.data.stored").value(1)).andExpect(jsonPath("$.data.lastSeq").value(201))
                .andExpect(jsonPath("$.data.dataGap").value(false));

        WecomMessage message = repository.findMessage(1L, CORP, msgId).orElseThrow();
        assertThat(message.customerId()).isEqualTo(customerId);
        assertThat(message.staffUserId()).isEqualTo(2);
        assertThat(archiveService.readContent(message)).isEqualTo("官方协议测试消息");
        assertThat(objects).hasSize(1);
        assertThat(new String(objects.values().iterator().next(), StandardCharsets.UTF_8))
                .doesNotContain("官方协议测试消息");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_type='wecom.message.archived.v1' "
                + "AND aggregate_id=?", message.id().toString())).isEqualTo(1);
        assertThat(queryLong("SELECT last_seq FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope='OFFICIAL_ARCHIVE'", CORP)).isEqualTo(201);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope='MOCK_ARCHIVE'", CORP)).isZero();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/integrations/wecom/monitor").param("corpId", CORP).cookie(login("admin").cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.archiveScope").value("OFFICIAL_ARCHIVE"))
                .andExpect(jsonPath("$.data.lastSeq").value(201));

        sync(1)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicates").value(1))
                .andExpect(jsonPath("$.data.stored").value(0));
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?",
                CORP, msgId)).isEqualTo(1);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 AND event_type='wecom.message.archived.v1' "
                + "AND aggregate_id=?", message.id().toString())).isEqualTo(1);
        verify(session, org.mockito.Mockito.times(2)).close();
    }

    @Test
    void fileMessageRegistersAssetDownloadsChunksAndEnforcesReadScope() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String external = "wm-media-" + suffix;
        String msgId = "OFFICIAL-MEDIA-" + suffix;
        String sdkFileId = "sdk-media-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'媒体归档测试客户')", "MEDIA-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "MEDIA-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',2,1) ON DUPLICATE KEY UPDATE enabled=1", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code) "
                + "VALUES (1,?,2,1,'OFFICIAL') ON DUPLICATE KEY UPDATE enabled=1,source_code='OFFICIAL'", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,follow_status) VALUES (1,?,?,2,?,'GRANTED','synthetic://consent','ACTIVE')",
                CORP, external, customerId);
        byte[] mediaBytes = "media-content".getBytes(StandardCharsets.UTF_8);
        String md5 = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(mediaBytes));
        String plaintext = "{\"msgid\":\"" + msgId + "\",\"action\":\"send\",\"from\":\"" + external + "\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000000000,\"msgtype\":\"file\","
                + "\"file\":{\"md5sum\":\"" + md5 + "\",\"filesize\":" + mediaBytes.length
                + ",\"filename\":\"test.txt\",\"fileext\":\"txt\",\"sdkfileid\":\"" + sdkFileId + "\"}}";
        String page = "{\"errcode\":0,\"chatdata\":[{\"seq\":202,\"msgid\":\"" + msgId + "\","
                + "\"publickey_ver\":3,\"encrypt_random_key\":\"random-media\",\"encrypt_chat_msg\":\"cipher-media\"}]}";
        when(session.pullChatData(0, 100)).thenReturn(page);
        when(session.decrypt("synthetic-random-key", "cipher-media")).thenReturn(plaintext);
        when(session.downloadMedia(sdkFileId, "")).thenReturn(new com.whale.crm.integration.wecom.adapter.WecomMediaChunk(
                "media-".getBytes(StandardCharsets.UTF_8), "next-chunk", false));
        when(session.downloadMedia(sdkFileId, "next-chunk")).thenReturn(new com.whale.crm.integration.wecom.adapter.WecomMediaChunk(
                "content".getBytes(StandardCharsets.UTF_8), "", true));

        sync(2).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1));
        WecomMessage message = repository.findMessage(1L, CORP, msgId).orElseThrow();
        var pending = mediaRepository.findByMessage(1L, message.id());
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().status()).isEqualTo("PENDING");
        long assetId = pending.getFirst().id();
        assertThat(mediaWorker.runOnce()).isEqualTo(1);
        var stored = mediaRepository.findById(1L, assetId).orElseThrow();
        assertThat(stored.status()).isEqualTo("STORED");
        assertThat(stored.actualSize()).isEqualTo(mediaBytes.length);
        assertThat(stored.contentSha256()).isEqualTo(java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(mediaBytes)));
        assertThat(stored.objectKey()).doesNotContain(sdkFileId);

        AuthCookies admin = login("admin");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/integrations/wecom/messages/" + message.id() + "/content").cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("test.txt"))
                .andExpect(jsonPath("$.data.mediaAssets[0].available").value(true))
                .andExpect(jsonPath("$.data.mediaAssets[0].status").value("STORED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/integrations/wecom/media/" + assetId + "/content").cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().bytes(mediaBytes));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/integrations/wecom/media/" + assetId + "/content").cookie(login("manager2").cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void recallCanArriveBeforeOriginalAndReplayDoesNotDuplicateEvents() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String external = "wm-recall-" + suffix;
        String originalId = "RECALL-ORIGINAL-" + suffix;
        String recallId = "RECALL-EVENT-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'撤回测试客户')", "RECALL-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "RECALL-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',2,1) ON DUPLICATE KEY UPDATE enabled=1", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code) "
                + "VALUES (1,?,2,1,'OFFICIAL') ON DUPLICATE KEY UPDATE enabled=1,source_code='OFFICIAL'", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,follow_status) VALUES (1,?,?,2,?,'GRANTED','synthetic://consent','ACTIVE')",
                CORP, external, customerId);
        String recallText = "{\"msgid\":\"" + recallId + "\",\"action\":\"recall\",\"from\":\"" + external + "\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000001000,\"msgtype\":\"revoke\","
                + "\"revoke\":{\"pre_msgid\":\"" + originalId + "\"}}";
        String originalText = "{\"msgid\":\"" + originalId + "\",\"action\":\"send\",\"from\":\"" + external + "\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"撤回前原始消息\"}}";
        String recallPage = page(301, recallId, "cipher-recall");
        String originalPage = page(302, originalId, "cipher-original");
        when(session.pullChatData(0, 100)).thenReturn(recallPage, originalPage, recallPage);
        when(session.decrypt("synthetic-random-key", "cipher-recall")).thenReturn(recallText);
        when(session.decrypt("synthetic-random-key", "cipher-original")).thenReturn(originalText);

        sync(3).andExpect(status().isOk()).andExpect(jsonPath("$.data.recalled").value(1))
                .andExpect(jsonPath("$.data.stored").value(0)).andExpect(jsonPath("$.data.dataGap").value(true));
        var pending = jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_recall_event WHERE tenant_id=1 "
                + "AND corp_id=? AND recall_message_id=?", String.class, CORP, recallId);
        assertThat(pending).isEqualTo("PENDING_ORIGINAL");

        sync(4).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(1))
                .andExpect(jsonPath("$.data.dataGap").value(false));
        WecomMessage original = repository.findMessage(1L, CORP, originalId).orElseThrow();
        assertThat(original.recalledAt()).isNotNull();
        assertThat(original.recallMessageId()).isEqualTo(recallId);
        assertThat(archiveService.readContent(original)).isEqualTo("撤回前原始消息");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_wecom_recall_event WHERE tenant_id=1 "
                + "AND corp_id=? AND recall_message_id=?", String.class, CORP, recallId)).isEqualTo("APPLIED");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='RECALL_ORIGINAL_PENDING' AND status='OPEN'", CORP)).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='wecom.message.recalled.v1' AND aggregate_id=?", original.id().toString())).isEqualTo(1);

        sync(5).andExpect(status().isOk()).andExpect(jsonPath("$.data.recalled").value(0));
        assertThat(queryLong("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='wecom.message.recalled.v1' AND aggregate_id=?", original.id().toString())).isEqualTo(1);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/integrations/wecom/messages/" + original.id() + "/content").cookie(login("admin").cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("撤回前原始消息"))
                .andExpect(jsonPath("$.data.recalledAt").isNotEmpty());
    }

    private String page(long seq, String msgId, String cipher) {
        return "{\"errcode\":0,\"chatdata\":[{\"seq\":" + seq + ",\"msgid\":\"" + msgId + "\","
                + "\"publickey_ver\":3,\"encrypt_random_key\":\"random-recall\",\"encrypt_chat_msg\":\""
                + cipher + "\"}]}";
    }

    private org.springframework.test.web.servlet.ResultActions sync(int suffix) throws Exception {
        AuthCookies admin = login("admin");
        return mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/sync"))
                .header("Idempotency-Key", "official-it-" + suffix + "-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("corpId", CORP, "cursor", 0, "limit", 100))));
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }
}
