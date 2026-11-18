package com.whale.crm.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"app.ai.worker.enabled=false", "app.wecom.message.mode=DISABLED"})
class ComplaintDetectionIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AesGcmCipher cipher;
    @MockitoBean
    private PrivateObjectStorage objectStorage;

    @Test
    void wecomDetectionRechecksConsentCoverageTextAndEmployeeBeforeReadingContent() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix();
        newKeyword(admin, "投诉" + suffix);
        WecomFixture valid = newWecom(suffix + "a", "GRANTED", true, "TEXT", true,
                "我要投诉" + suffix + "，电话13800138000");
        JsonNode detected = data(admin.withAuth(post("/api/v1/complaint-detection/wecom-messages/" + valid.messageId()))
                .header("Idempotency-Key", "wecom-valid-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))));
        assertThat(detected.at("/hits").size()).isEqualTo(1);
        assertThat(detected.at("/hits/0/evidenceMasked").asText()).contains("138****8000")
                .doesNotContain("13800138000");
        verify(objectStorage).getObject(valid.objectKey());

        WecomFixture revoked = newWecom(suffix + "b", "REVOKED", true, "TEXT", true, "投诉" + suffix);
        JsonNode revokedResult = detect(admin, revoked, "wecom-revoked-" + suffix);
        assertThat(revokedResult.at("/hits")).isEmpty();
        verify(objectStorage, never()).getObject(revoked.objectKey());

        WecomFixture uncovered = newWecom(suffix + "c", "GRANTED", false, "TEXT", true, "投诉" + suffix);
        assertThat(detect(admin, uncovered, "wecom-uncovered-" + suffix).at("/hits")).isEmpty();
        verify(objectStorage, never()).getObject(uncovered.objectKey());

        WecomFixture image = newWecom(suffix + "d", "GRANTED", true, "IMAGE", true, "投诉" + suffix);
        assertThat(detect(admin, image, "wecom-image-" + suffix).at("/hits")).isEmpty();
        verify(objectStorage, never()).getObject(image.objectKey());

        WecomFixture disabledStaff = newWecom(suffix + "e", "GRANTED", true, "TEXT", false, "投诉" + suffix);
        assertThat(detect(admin, disabledStaff, "wecom-staff-" + suffix).at("/hits")).isEmpty();
        verify(objectStorage, never()).getObject(disabledStaff.objectKey());
    }

    @Test
    void recipientResolutionUsesTeamLeaderThenSingleActiveManagerLeadThenFallbackWithoutBroadcast() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix();
        newKeyword(admin, "差评" + suffix);

        long managerCustomer = newCustomer(3L, 2L, "IT-COMPLAINT-MANAGER-" + suffix);
        long managerFollowup = newFollowup(managerCustomer, "客户给差评" + suffix);
        JsonNode managerHit = detectFollowup(admin, managerFollowup, "manager-route-" + suffix).at("/hits/0");
        assertThat(managerHit.at("/recipientUserIdSnapshot").asLong()).isEqualTo(9);
        assertThat(managerHit.at("/recipientSourceSnapshot").asText()).isEqualTo("TEAM_MANAGER_LEAD");
        assertThat(notificationCount(managerHit.at("/id").asLong())).isEqualTo(1);

        jdbcTemplate.update("UPDATE crm_complaint_policy SET fallback_recipient_user_id=NULL,version=version+1 WHERE tenant_id=1");
        jdbcTemplate.update("INSERT INTO sys_team (tenant_id,org_id,team_name,leader_user_id,status) "
                + "VALUES (1,1,? ,NULL,'ACTIVE')", "无主管团队" + suffix);
        long teamId = queryLong("SELECT id FROM sys_team WHERE tenant_id=1 AND team_name=?", "无主管团队" + suffix);
        long isolatedStaff = newStaff("isolated-" + suffix, teamId, "ACTIVE");
        long isolatedCustomer = newCustomer(isolatedStaff, teamId, "IT-COMPLAINT-ISOLATED-" + suffix);
        long noRecipientFollowup = newFollowup(isolatedCustomer, "客户给差评" + suffix);
        JsonNode noRecipient = detectFollowup(admin, noRecipientFollowup, "no-recipient-" + suffix).at("/hits/0");
        assertThat(noRecipient.at("/routingStatus").asText()).isEqualTo("EXCEPTION");
        assertThat(noRecipient.at("/routingError").asText()).isEqualTo("NO_RECIPIENT");
        assertThat(noRecipient.at("/recipientUserIdSnapshot").isNull()).isTrue();
        assertThat(notificationCount(noRecipient.at("/id").asLong())).isZero();

        JsonNode settings = data(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/complaint-automation/settings").cookie(admin.cookies()));
        data(admin.withAuth(patch("/api/v1/complaint-automation/settings"))
                .header("Idempotency-Key", "fallback-policy-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                        "enabled", true, "fallbackRecipientUserId", 1, "empathyScript", settings.at("/empathyScript").asText(),
                        "version", settings.at("/version").asLong(), "reason", "验证唯一兜底接收人"))));
        long fallbackFollowup = newFollowup(isolatedCustomer, "再次给差评" + suffix);
        JsonNode fallback = detectFollowup(admin, fallbackFollowup, "fallback-route-" + suffix).at("/hits/0");
        assertThat(fallback.at("/recipientUserIdSnapshot").asLong()).isEqualTo(1);
        assertThat(fallback.at("/recipientSourceSnapshot").asText()).isEqualTo("FALLBACK");
        assertThat(notificationCount(fallback.at("/id").asLong())).isEqualTo(1);
        assertThat(noRecipient.at("/routingStatus").asText()).isEqualTo("EXCEPTION");
    }

    private JsonNode detect(AuthCookies admin, WecomFixture fixture, String key) throws Exception {
        return data(admin.withAuth(post("/api/v1/complaint-detection/wecom-messages/" + fixture.messageId()))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0))));
    }

    private JsonNode detectFollowup(AuthCookies admin, long followupId, String key) throws Exception {
        return data(admin.withAuth(post("/api/v1/complaint-detection/followups/" + followupId))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0))));
    }

    private void newKeyword(AuthCookies admin, String keyword) throws Exception {
        data(admin.withAuth(post("/api/v1/complaint-automation/keywords"))
                .header("Idempotency-Key", "keyword-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword))));
    }

    private WecomFixture newWecom(String suffix, String consent, boolean covered, String type,
                                  boolean staffActive, String text) throws Exception {
        long staffId = newStaff("wecom-" + suffix, 1L, staffActive ? "ACTIVE" : "DISABLED");
        long customerId = newCustomer(staffId, 1L, "IT-COMPLAINT-WECOM-" + suffix);
        String corpId = "corp-" + suffix;
        String externalId = "external-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                        + "consent_status,consent_evidence_ref,link_type,link_reason,created_by) "
                        + "VALUES (1,?,?,?,?,?,'synthetic-consent','MANUAL','合成测试映射',1)",
                corpId, externalId, staffId, customerId, consent);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                + "VALUES (1,?,?,?,'MOCK','synthetic-member')", corpId, staffId, covered ? 1 : 0);
        String objectKey = "synthetic/complaint/" + suffix + ".json.enc";
        String raw = objectMapper.writeValueAsString(Map.of("messageType", type, "text", text));
        byte[] encrypted = cipher.encrypt(raw).getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encrypted));
        jdbcTemplate.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,external_userid,"
                        + "staff_user_id,conversation_id,message_type,sender_type,sent_at,content_object_key,content_sha256,"
                        + "consent_snapshot,consent_evidence_ref,status) VALUES (1,?,?,?, ?,?,?,'conversation-" + suffix + "',?,"
                        + "'CUSTOMER',CURRENT_TIMESTAMP(3),?,?,'GRANTED','synthetic-consent','STORED')",
                corpId, "msg-" + suffix, 1001, customerId, externalId, staffId, type, objectKey, hash);
        long messageId = queryLong("SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?",
                corpId, "msg-" + suffix);
        when(objectStorage.getObject(objectKey)).thenAnswer(invocation -> new ByteArrayInputStream(encrypted));
        return new WecomFixture(messageId, objectKey);
    }

    private long newStaff(String username, Long teamId, String status) {
        jdbcTemplate.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,"
                        + "user_type,status) VALUES (1,1,?,?,'合成客诉员工',"
                        + "'$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE',?)",
                teamId, username, status);
        return queryLong("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", username);
    }

    private long newCustomer(long ownerId, Long teamId, String customerNo) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,?,?,?,'合成客诉客户')", ownerId, teamId, customerNo);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?", customerNo);
    }

    private long newFollowup(long customerId, String summary) {
        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,summary,created_by) "
                + "SELECT tenant_id,id,owner_staff_id,'PHONE',?,owner_staff_id FROM crm_customer WHERE id=?", summary, customerId);
        return queryLong("SELECT id FROM crm_followup WHERE tenant_id=1 AND customer_id=? ORDER BY id DESC LIMIT 1", customerId);
    }

    private long notificationCount(long hitId) {
        return queryLong("SELECT COUNT(*) FROM crm_notification WHERE tenant_id=1 AND source_type='COMPLAINT_HIT' "
                + "AND source_id=?", Long.toString(hitId));
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andExpect(org.springframework.test.web.servlet.result
                        .MockMvcResultMatchers.status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record WecomFixture(long messageId, String objectKey) {
    }
}
