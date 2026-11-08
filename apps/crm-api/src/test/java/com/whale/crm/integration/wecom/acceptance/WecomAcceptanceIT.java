package com.whale.crm.integration.wecom.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WecomAcceptanceIT extends AbstractMysqlIT {
    private static final String CORP = MockWecomAdapter.CORP_ID;
    private static final String SCOPE = MockWecomAdapter.SCOPE;
    private static final String MSG_1 = "WECOM-MSG-001";
    private static final String MSG_2 = "WECOM-MSG-002";
    private static final String MSG_3 = "WECOM-MSG-003";
    private static final String TEXT_1 = "虚构Mock客户：想预约基础水光项目";
    private static final String TEXT_2 = "虚构未关联客户：想咨询套餐";
    private static final String TEXT_3 = "虚构撤回同意消息：不得归档";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockWecomAdapter adapter;
    @Autowired
    private AesGcmCipher cipher;

    @MockitoBean
    private PrivateObjectStorage storage;

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies manager2;
    private AuthCookies operator1;
    private AuthCookies operator2;

    @BeforeAll
    void loginUsers() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        manager2 = login("manager2");
        operator1 = login("operator1");
        operator2 = login("operator2");
    }

    @BeforeEach
    void setUpFixture() {
        resetFixture();
        Mockito.reset(storage);
        objects.clear();
        stubPutObject();
        stubDeleteObject();
        when(storage.getObject(anyString())).thenAnswer(invocation -> {
            byte[] content = objects.get(invocation.getArgument(0));
            if (content == null) {
                throw new IllegalStateException("Synthetic object does not exist");
            }
            return new ByteArrayInputStream(content);
        });
    }

    @AfterEach
    void restoreFixture() {
        resetFixture();
        objects.clear();
    }

    @Test
    void w01OneHundredConcurrentAndReplayPullsStoreTwoMessagesTwoObjectsAndTwoOutboxEvents()
            throws Exception {
        int calls = 100;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JsonNode>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < calls; i++) {
                Long cursor = i % 2 == 0 ? 0L : null;
                futures.add(executor.submit(() -> {
                    start.await();
                    return data(syncRequest(admin, cursor, 100));
                }));
            }
            start.countDown();
            int stored = 0;
            for (Future<JsonNode> future : futures) {
                JsonNode response = future.get(60, TimeUnit.SECONDS);
                assertThat(response.at("/adapter").asText()).isEqualTo("MOCK_DEV_ONLY");
                assertThat(response.at("/lastSeq").asLong()).isEqualTo(103L);
                assertThat(response.at("/hasMore").asBoolean()).isFalse();
                assertThat(response.at("/dataGap").asBoolean()).isTrue();
                stored += response.at("/stored").asInt();
            }
            assertThat(stored).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        List<Map<String, Object>> messages = messageRows();
        assertThat(messages).hasSize(2);
        assertThat(messages.stream().map(row -> row.get("msg_id"))).containsExactly(MSG_1, MSG_2);
        assertThat(messages.stream().map(row -> ((Number) row.get("archive_seq")).longValue()))
                .containsExactly(101L, 102L);
        assertThat(((Number) messages.get(0).get("customer_id")).longValue()).isEqualTo(1010L);
        assertThat(messages.get(0).get("status")).isEqualTo("STORED");
        assertThat(messages.get(1).get("customer_id")).isNull();
        assertThat(messages.get(1).get("status")).isEqualTo("UNMATCHED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?",
                CORP, MSG_3)).isZero();
        assertThat(objects).hasSize(2);
        assertThat(objects.keySet()).containsExactlyInAnyOrderElementsOf(messages.stream()
                .map(row -> (String) row.get("content_object_key")).toList());
        verify(storage, times(2)).putObject(anyString(), any(byte[].class), anyString());
        assertThat(archivedOutboxCount()).isEqualTo(2);
        assertThat(queryLong("SELECT last_seq FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isEqualTo(103L);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='CONSENT_NOT_GRANTED' AND from_seq=103 AND status='OPEN'", CORP)).isEqualTo(1);
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void w02NeverSyncedAndPullFailureAreDataGapAndRecoveryDoesNotSkipOrEraseHistory() throws Exception {
        JsonNode never = monitor();
        assertThat(never.at("/status").asText()).isEqualTo("NEVER_SYNCED");
        assertThat(never.at("/lastSeq").asLong()).isZero();
        assertThat(never.at("/messageCount").asLong()).isZero();
        assertThat(never.at("/dataGap").asBoolean()).isTrue();
        assertThat(never.at("/gapReason").asText()).contains("无法判断").doesNotContain("无沟通");
        assertThat(never.at("/activeGaps").asLong()).isZero();
        assertThat(never.at("/historicalGaps").asLong()).isZero();

        adapter.failNextPull();
        mockMvc.perform(syncRequest(admin, null, 100))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        assertThat(queryLong("SELECT last_seq FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isZero();
        assertThat(queryString("SELECT status FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isEqualTo("ERROR");
        assertThat(queryInt("SELECT consecutive_failures FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isEqualTo(1);
        assertThat(messageRows()).isEmpty();
        assertThat(objects).isEmpty();
        assertThat(archivedOutboxCount()).isZero();
        JsonNode failed = monitor();
        assertThat(failed.at("/dataGap").asBoolean()).isTrue();
        assertThat(failed.at("/status").asText()).isEqualTo("ERROR");
        assertThat(failed.at("/lastSeq").asLong()).isZero();
        assertThat(failed.at("/activeGaps").asLong()).isEqualTo(1);
        assertThat(failed.at("/historicalGaps").asLong()).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='COLLECTOR_ERROR' AND from_seq=0 AND status='OPEN'", CORP)).isEqualTo(1);

        JsonNode recovered = data(syncRequest(admin, null, 100));
        assertThat(recovered.at("/stored").asInt()).isEqualTo(2);
        assertThat(recovered.at("/lastSeq").asLong()).isEqualTo(103L);
        assertThat(messageRows()).hasSize(2);
        assertThat(objects).hasSize(2);
        assertThat(archivedOutboxCount()).isEqualTo(2);
        JsonNode monitorAfterRecovery = monitor();
        assertThat(monitorAfterRecovery.at("/lastSeq").asLong()).isEqualTo(103L);
        assertThat(monitorAfterRecovery.at("/status").asText()).isEqualTo("SUCCESS");
        assertThat(monitorAfterRecovery.at("/dataGap").asBoolean()).isTrue();
        assertThat(monitorAfterRecovery.at("/historicalGaps").asLong()).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='COLLECTOR_ERROR' AND from_seq=0 AND status='RESOLVED' "
                + "AND resolved_at IS NOT NULL", CORP)).isEqualTo(1);

        assertThat(data(syncRequest(admin, null, 100)).at("/stored").asInt()).isZero();
        JsonNode laterSuccess = monitor();
        assertThat(laterSuccess.at("/lastSeq").asLong()).isEqualTo(103L);
        assertThat(laterSuccess.at("/historicalGaps").asLong()).isEqualTo(1);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='COLLECTOR_ERROR' AND from_seq=0", CORP)).isEqualTo(1);
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void revokedConsentIsDataGapDoesNotArchiveThirdMessageAndRevokesExistingFullText() throws Exception {
        JsonNode response = data(syncRequest(admin, null, 100));
        assertThat(response.at("/stored").asInt()).isEqualTo(2);
        assertThat(response.at("/skipped").asInt()).isEqualTo(1);
        assertThat(response.at("/dataGap").asBoolean()).isTrue();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?",
                CORP, MSG_3)).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='CONSENT_NOT_GRANTED' AND from_seq=103 AND status='OPEN'", CORP)).isEqualTo(1);
        JsonNode monitor = monitor();
        assertThat(monitor.at("/dataGap").asBoolean()).isTrue();
        assertThat(monitor.at("/gapReason").asText()).contains("同意");
        assertThat(monitor.at("/activeGaps").asLong()).isEqualTo(1);
        long firstMessage = messageId(MSG_1);
        assertThat(data(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                .cookie(manager1.cookies())).at("/content").asText()).isEqualTo(TEXT_1);

        jdbcTemplate.update("UPDATE crm_wecom_contact SET consent_status='REVOKED',version=version+1 "
                + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2", CORP);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                        .cookie(manager1.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(data(get("/api/v1/integrations/wecom/messages").cookie(manager1.cookies()))
                .at("/total").asLong()).isZero();
        assertThat(monitor().at("/dataGap").asBoolean()).isTrue();
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void uncoveredMemberMessagesAreNotStoredAndDisablingMemberRevokesFullText() throws Exception {
        disableMember(2);
        JsonNode skipped = data(syncRequest(admin, null, 100));
        assertThat(skipped.at("/stored").asInt()).isZero();
        assertThat(skipped.at("/skipped").asInt()).isEqualTo(3);
        assertThat(skipped.at("/lastSeq").asLong()).isEqualTo(103L);
        assertThat(skipped.at("/dataGap").asBoolean()).isTrue();
        assertThat(messageRows()).isEmpty();
        assertThat(objects).isEmpty();
        assertThat(archivedOutboxCount()).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=? "
                + "AND gap_type='MEMBER_NOT_COVERED' AND from_seq IN (101,102) AND status='OPEN'", CORP))
                .isEqualTo(2);
        assertThat(monitor().at("/activeGaps").asLong()).isEqualTo(3);

        resetFixture();
        Mockito.reset(storage);
        objects.clear();
        stubPutObject();
        stubDeleteObject();
        when(storage.getObject(anyString())).thenAnswer(invocation ->
                new ByteArrayInputStream(objects.get(invocation.getArgument(0))));
        data(syncRequest(admin, null, 100));
        long firstMessage = messageId(MSG_1);
        assertThat(data(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                .cookie(manager1.cookies())).at("/content").asText()).isEqualTo(TEXT_1);
        disableMember(2);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                        .cookie(manager1.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                        .cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        assertThat(data(get("/api/v1/integrations/wecom/messages").cookie(admin.cookies()))
                .at("/total").asLong()).isZero();
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void unmatchedContactRequiresManualLinkAndManagerTeamAndInstitutionScopesAreEnforced() throws Exception {
        int customersBefore = queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1");
        data(syncRequest(admin, null, 100));
        long secondMessage = messageId(MSG_2);
        long firstMessage = messageId(MSG_1);
        assertThat(queryString("SELECT status FROM crm_wecom_message WHERE tenant_id=1 AND id=?", secondMessage))
                .isEqualTo("UNMATCHED");
        assertThat(queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1")).isEqualTo(customersBefore);
        JsonNode unmatched = contactFromApi("WECOM-EXT-UNMATCHED");
        long contactId = unmatched.at("/id").asLong();
        long version = unmatched.at("/version").asLong();
        MockHttpServletRequestBuilder link = admin.withAuth(post("/api/v1/integrations/wecom/contacts/{id}/link", contactId))
                .header("Idempotency-Key", key("link"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", 1010, "version", version,
                        "reason", "人工核对企微外部身份，不按昵称或手机号自动合并")));

        mockMvc.perform(manager1.withAuth(post("/api/v1/integrations/wecom/contacts/{id}/link", contactId))
                        .header("Idempotency-Key", key("denied-link"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", 1010, "version", version, "reason", "越权人工匹配"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(queryInt("SELECT COUNT(*) FROM crm_wecom_contact WHERE tenant_id=1 AND id=? "
                + "AND customer_id IS NULL", contactId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", secondMessage)
                        .cookie(manager1.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                        .cookie(operator1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/messages").cookie(operator2.cookies()))
                .andExpect(status().isForbidden());

        JsonNode linked = data(link);
        assertThat(linked.at("/customerId").asLong()).isEqualTo(1010L);
        assertThat(linked.at("/linkType").asText()).isEqualTo("MANUAL");
        assertThat(queryString("SELECT status FROM crm_wecom_message WHERE tenant_id=1 AND id=?", secondMessage))
                .isEqualTo("STORED");
        assertThat(queryLong("SELECT customer_id FROM crm_wecom_message WHERE tenant_id=1 AND id=?", secondMessage))
                .isEqualTo(1010L);
        assertThat(data(get("/api/v1/integrations/wecom/messages/{id}/content", secondMessage)
                .cookie(manager1.cookies())).at("/content").asText()).isEqualTo(TEXT_2);
        assertThat(data(get("/api/v1/integrations/wecom/messages").cookie(manager1.cookies()))
                .at("/total").asLong()).isEqualTo(2);
        mockMvc.perform(get("/api/v1/integrations/wecom/messages/{id}/content", secondMessage)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        assertThat(data(get("/api/v1/integrations/wecom/messages").cookie(manager2.cookies()))
                .at("/total").asLong()).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_customer WHERE tenant_id=1")).isEqualTo(customersBefore);

        int messagesBeforeDeniedSync = messageRows().size();
        int objectsBeforeDeniedSync = objects.size();
        mockMvc.perform(syncRequest(manager1, 0L, 100))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(messageRows()).hasSize(messagesBeforeDeniedSync);
        assertThat(objects).hasSize(objectsBeforeDeniedSync);
        assertThat(archivedOutboxCount()).isEqualTo(2);
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void encryptedObjectHashListKafkaAndAuditExposeOnlyReferencesNotPlaintext() throws Exception {
        data(syncRequest(admin, null, 100));
        List<Map<String, Object>> rows = messageRows();
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("content_object_key");
            byte[] encrypted = objects.get(key);
            assertThat(encrypted).isNotNull().isNotEmpty();
            assertThat(row.get("content_sha256")).isEqualTo(sha256(encrypted));
            String encoded = new String(encrypted, StandardCharsets.UTF_8);
            assertThat(encoded).doesNotContain(TEXT_1, TEXT_2, TEXT_3);
            JsonNode decrypted = objectMapper.readTree(cipher.decrypt(encoded));
            String expectedText = MSG_1.equals(row.get("msg_id")) ? TEXT_1 : TEXT_2;
            assertThat(decrypted.at("/text").asText()).isEqualTo(expectedText);
            assertThat(decrypted.at("/messageType").asText()).isEqualTo("TEXT");
        }

        String listResponse = mockMvc.perform(get("/api/v1/integrations/wecom/messages")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(listResponse).doesNotContain(TEXT_1, TEXT_2, TEXT_3);
        JsonNode list = objectMapper.readTree(listResponse).at("/data/items");
        list.forEach(item -> {
            assertThat(item.has("content")).isFalse();
            assertThat(item.at("/contentObjectKey").asText()).isNotBlank();
            assertThat(item.at("/id").asLong()).isPositive();
        });
        for (String msgId : List.of(MSG_1, MSG_2)) {
            JsonNode payload = outboxPayload("wecom.message.archived.v1:" + CORP + ":" + msgId);
            assertThat(payload.at("/messageRef").asLong()).isEqualTo(messageId(msgId));
            assertThat(payload.at("/contentObjectKey").asText()).isNotBlank();
            assertThat(payload.has("text")).isFalse();
            assertThat(payload.has("content")).isFalse();
        }
        long firstMessage = messageId(MSG_1);
        assertThat(data(get("/api/v1/integrations/wecom/messages/{id}/content", firstMessage)
                .cookie(manager1.cookies())).at("/content").asText()).isEqualTo(TEXT_1);
        assertNoPlaintextOutsideControlledContent();
    }

    @Test
    void secondObjectFailureRollsBackDatabaseAndFirstObjectThenRetryDoesNotSkipInterval() throws Exception {
        AtomicInteger puts = new AtomicInteger();
        doAnswer(invocation -> {
            if (puts.incrementAndGet() == 2) {
                throw new IllegalStateException("Synthetic second object failure");
            }
            byte[] content = ((byte[]) invocation.getArgument(1)).clone();
            objects.put(invocation.getArgument(0), content);
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());

        mockMvc.perform(syncRequest(admin, null, 100))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(messageRows()).isEmpty();
        assertThat(archivedOutboxCount()).isZero();
        assertThat(objects).isEmpty();
        assertThat(queryLong("SELECT last_seq FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isZero();
        assertThat(queryString("SELECT status FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=? "
                + "AND archive_scope=?", CORP, SCOPE)).isEqualTo("ERROR");
        verify(storage).deleteObject(anyString());

        stubPutObject();
        JsonNode recovered = data(syncRequest(admin, null, 100));
        assertThat(recovered.at("/stored").asInt()).isEqualTo(2);
        assertThat(recovered.at("/lastSeq").asLong()).isEqualTo(103L);
        assertThat(messageRows()).hasSize(2);
        assertThat(objects).hasSize(2);
        assertThat(archivedOutboxCount()).isEqualTo(2);
        assertThat(monitor().at("/historicalGaps").asLong()).isEqualTo(1);
        assertNoPlaintextOutsideControlledContent();
    }

    private void resetFixture() {
        consumeFailureFlag();
        jdbcTemplate.update("DELETE FROM crm_audit_log WHERE tenant_id=1 AND action_code LIKE 'WECOM_%'");
        jdbcTemplate.update("DELETE FROM crm_outbox_event WHERE tenant_id=1 AND (event_id LIKE ? OR event_id LIKE ?)",
                "wecom.message.archived.v1:" + CORP + ":%", "wecom.archive.gap.v1:" + CORP + ":%");
        jdbcTemplate.update("DELETE FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_data_gap WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_cursor WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("DELETE FROM crm_wecom_archive_member WHERE tenant_id=1 AND corp_id=?", CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                + "VALUES (1,?,2,1,'MOCK','mock://archive-member/staff-2'),"
                + "(1,?,3,1,'MOCK','mock://archive-member/staff-3')", CORP, CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,consent_evidence_ref,link_type,link_reason,created_by) VALUES "
                + "(1,?,'WECOM-EXT-1010',2,1010,'GRANTED','mock://consent/1010','MANUAL','DEV ONLY：虚构人工确认',1),"
                + "(1,?,'WECOM-EXT-UNMATCHED',2,NULL,'GRANTED','mock://consent/unmatched',NULL,NULL,NULL),"
                + "(1,?,'WECOM-EXT-REVOKED',3,NULL,'REVOKED','mock://consent/revoked',NULL,NULL,NULL)", CORP, CORP, CORP);
        jdbcTemplate.update("INSERT INTO crm_wecom_cursor (tenant_id,corp_id,archive_scope,last_seq,status,data_gap,gap_reason) "
                + "VALUES (1,?,?,0,'NEVER_SYNCED',1,'尚未采集，无法判断沟通情况')", CORP, SCOPE);
    }

    private void consumeFailureFlag() {
        try {
            adapter.pullArchive(CORP, 0, 1);
        } catch (RuntimeException ignored) {
        }
    }

    private void disableMember(long staffId) {
        jdbcTemplate.update("UPDATE crm_wecom_archive_member SET enabled=0,version=version+1 "
                + "WHERE tenant_id=1 AND corp_id=? AND staff_user_id=?", CORP, staffId);
    }

    private void stubPutObject() {
        doAnswer(invocation -> {
            byte[] content = ((byte[]) invocation.getArgument(1)).clone();
            objects.put(invocation.getArgument(0), content);
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
    }

    private void stubDeleteObject() {
        doAnswer(invocation -> {
            objects.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());
    }

    private MockHttpServletRequestBuilder syncRequest(AuthCookies actor, Long cursor, int limit) throws Exception {
        Map<String, Object> body = cursor == null ? Map.of("corpId", CORP, "limit", limit)
                : Map.of("corpId", CORP, "cursor", cursor, "limit", limit);
        return actor.withAuth(post("/api/v1/integrations/wecom/sync"))
                .header("Idempotency-Key", key("sync"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body));
    }

    private JsonNode monitor() throws Exception {
        return data(get("/api/v1/integrations/wecom/monitor").param("corpId", CORP).cookie(admin.cookies()));
    }

    private JsonNode contactFromApi(String externalUserid) throws Exception {
        JsonNode contacts = data(get("/api/v1/integrations/wecom/contacts").cookie(admin.cookies()));
        List<JsonNode> matches = new ArrayList<>();
        contacts.forEach(contact -> {
            if (externalUserid.equals(contact.at("/externalUserid").asText())) {
                matches.add(contact);
            }
        });
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private List<Map<String, Object>> messageRows() {
        return jdbcTemplate.queryForList("SELECT id,msg_id,archive_seq,customer_id,content_object_key,content_sha256,status "
                + "FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? ORDER BY archive_seq", CORP);
    }

    private long messageId(String msgId) {
        return queryLong("SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?", CORP, msgId);
    }

    private int archivedOutboxCount() {
        return queryInt("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND event_type='wecom.message.archived.v1' AND event_id LIKE ?",
                "wecom.message.archived.v1:" + CORP + ":%");
    }

    private JsonNode outboxPayload(String eventId) throws Exception {
        return objectMapper.readTree(queryString("SELECT payload_json FROM crm_outbox_event "
                + "WHERE tenant_id=1 AND event_id=?", eventId));
    }

    private void assertNoPlaintextOutsideControlledContent() {
        objects.values().forEach(content -> assertThat(new String(content, StandardCharsets.UTF_8))
                .doesNotContain(TEXT_1, TEXT_2, TEXT_3));
        List<String> payloads = jdbcTemplate.queryForList("SELECT payload_json FROM crm_outbox_event "
                + "WHERE tenant_id=1 AND (event_id LIKE ? OR event_id LIKE ?)", String.class,
                "wecom.message.archived.v1:" + CORP + ":%", "wecom.archive.gap.v1:" + CORP + ":%");
        payloads.forEach(payload -> assertThat(payload).doesNotContain(TEXT_1, TEXT_2, TEXT_3));
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("SELECT before_json,after_json,reason "
                + "FROM crm_audit_log WHERE tenant_id=1 AND action_code LIKE 'WECOM_%'");
        audits.forEach(audit -> audit.values().forEach(value -> {
            if (value != null) {
                assertThat(value.toString()).doesNotContain(TEXT_1, TEXT_2, TEXT_3);
            }
        }));
        List<Map<String, Object>> messages = jdbcTemplate.queryForList("SELECT content_object_key,content_sha256 "
                + "FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=?", CORP);
        messages.forEach(message -> message.values().forEach(value -> {
            if (value != null) {
                assertThat(value.toString()).doesNotContain(TEXT_1, TEXT_2, TEXT_3);
            }
        }));
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-p12-" + prefix + "-" + UUID.randomUUID();
    }
}
