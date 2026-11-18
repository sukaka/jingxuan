package com.whale.crm.complaint;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 测试矩阵：配置 CSRF/版本/幂等/审计；规则检测在 AI 停用时仍运行；命中不自动转正式单；
 * 团队路由快照和脱敏双渠道通知；跨团队/租户/机构隔离；正式单合法与非法状态转换、追加历史不可覆盖。
 */
class ComplaintApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void keywordAndPolicyWritesRequireCsrfVersionIdempotencyAndAudit() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix();
        String keyword = "合成客诉" + suffix;

        mockMvc.perform(post("/api/v1/complaint-automation/keywords").cookie(admin.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword)))
                        .header("Idempotency-Key", "missing-csrf-" + suffix))
                .andExpect(status().isForbidden());

        JsonNode created = data(admin.withAuth(post("/api/v1/complaint-automation/keywords"))
                .header("Idempotency-Key", "keyword-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword))));
        long keywordId = created.at("/id").asLong();
        assertThat(created.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(created.at("/version").asLong()).isZero();

        JsonNode replay = data(admin.withAuth(post("/api/v1/complaint-automation/keywords"))
                .header("Idempotency-Key", "keyword-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword))));
        assertThat(replay.at("/id").asLong()).isEqualTo(keywordId);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_keyword WHERE tenant_id=1 AND keyword=?", keyword))
                .isEqualTo(1);
        mockMvc.perform(admin.withAuth(post("/api/v1/complaint-automation/keywords"))
                        .header("Idempotency-Key", "keyword-create-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword + "不同"))))
                .andExpect(status().isConflict());

        mockMvc.perform(admin.withAuth(patch("/api/v1/complaint-automation/keywords/" + keywordId))
                        .header("Idempotency-Key", "keyword-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("keyword", keyword + "改", "version", 99, "reason", "错误版本"))))
                .andExpect(status().isConflict());
        JsonNode updated = data(admin.withAuth(patch("/api/v1/complaint-automation/keywords/" + keywordId))
                .header("Idempotency-Key", "keyword-update-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("keyword", keyword + "改", "version", 0, "reason", "人工修订关键词"))));
        assertThat(updated.at("/version").asLong()).isEqualTo(1);
        JsonNode disabled = data(admin.withAuth(post("/api/v1/complaint-automation/keywords/" + keywordId + "/disable"))
                .header("Idempotency-Key", "keyword-disable-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "reason", "不再用于新检测"))));
        assertThat(disabled.at("/status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.at("/version").asLong()).isEqualTo(2);

        JsonNode settings = data(get("/api/v1/complaint-automation/settings").cookie(admin.cookies()));
        mockMvc.perform(admin.withAuth(patch("/api/v1/complaint-automation/settings"))
                        .header("Idempotency-Key", "policy-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                                "enabled", false, "fallbackRecipientUserId", 1,
                                "empathyScript", "我们理解您的感受，会由专人核实。",
                                "version", 99, "reason", "错误版本"))))
                .andExpect(status().isConflict());
        JsonNode policy = data(admin.withAuth(patch("/api/v1/complaint-automation/settings"))
                .header("Idempotency-Key", "policy-update-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                        "enabled", true, "fallbackRecipientUserId", 1,
                        "empathyScript", "我们理解您的感受，会由专人核实。",
                        "version", settings.at("/version").asLong(), "reason", "配置客诉兜底主管"))));
        assertThat(policy.at("/fallbackRecipientUserId").asLong()).isEqualTo(1);
        assertThat(policy.at("/empathyScript").asText()).contains("专人核实");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND target_type='COMPLAINT_KEYWORD' AND target_id=?", Long.toString(keywordId))).isEqualTo(3);
    }

    @Test
    void followupDetectionCreatesOnlyOpenHitThenFormalWorkflowIsIdempotentAndAppendOnly() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead1 = login("lead1");
        AuthCookies manager2 = login("manager2");
        String suffix = suffix();
        String keyword = "投诉" + suffix;
        data(admin.withAuth(post("/api/v1/complaint-automation/keywords"))
                .header("Idempotency-Key", "detect-keyword-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("keyword", keyword))));
        long customerId = newCustomer(1L, 2L, 1L, "IT-COMPLAINT-CUSTOMER-" + suffix);
        long followupId = newFollowup(customerId, 2, "效果不好，我要" + keyword + "，联系电话13800138000");

        mockMvc.perform(manager2.withAuth(post("/api/v1/complaint-detection/followups/" + followupId))
                        .header("Idempotency-Key", "detect-denied-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))))
                .andExpect(status().isNotFound());
        JsonNode detection = data(lead1.withAuth(post("/api/v1/complaint-detection/followups/" + followupId))
                .header("Idempotency-Key", "detect-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))));
        assertThat(detection.at("/hits").size()).isEqualTo(1);
        JsonNode hit = detection.at("/hits/0");
        long hitId = hit.at("/id").asLong();
        assertThat(hit.at("/status").asText()).isEqualTo("OPEN");
        assertThat(hit.at("/recipientUserIdSnapshot").asLong()).isEqualTo(4);
        assertThat(hit.at("/recipientSourceSnapshot").asText()).isEqualTo("TEAM_LEADER");
        assertThat(hit.at("/routingStatus").asText()).isEqualTo("ROUTED");
        assertThat(hit.at("/evidenceMasked").asText()).contains("138****8000").doesNotContain("13800138000");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_case WHERE tenant_id=1 AND complaint_hit_id=?", hitId)).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_notification WHERE tenant_id=1 AND source_type='COMPLAINT_HIT' "
                + "AND source_id=?", Long.toString(hitId))).isEqualTo(1);
        Map<String, Object> notification = jdbcTemplate.queryForMap("SELECT * FROM crm_notification "
                + "WHERE tenant_id=1 AND source_type='COMPLAINT_HIT' AND source_id=?", Long.toString(hitId));
        assertThat(((Number) notification.get("recipient_user_id")).longValue()).isEqualTo(4);
        assertThat(notification.get("content_masked").toString()).doesNotContain("13800138000");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_notification_delivery WHERE tenant_id=1 AND notification_id=?",
                notification.get("id"))).isEqualTo(2);

        JsonNode detectionReplay = data(lead1.withAuth(post("/api/v1/complaint-detection/followups/" + followupId))
                .header("Idempotency-Key", "detect-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))));
        assertThat(detectionReplay.at("/hits/0/id").asLong()).isEqualTo(hitId);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_hit WHERE tenant_id=1 AND source_type='FOLLOWUP' "
                + "AND source_id=?", Long.toString(followupId))).isEqualTo(1);
        JsonNode scopedHits = data(get("/api/v1/complaint-hits").param("keyword", suffix).cookie(lead1.cookies()));
        assertThat(scopedHits.at("/total").asLong()).isEqualTo(1);
        assertThat(scopedHits.at("/items/0/id").asLong()).isEqualTo(hitId);
        JsonNode crossTeamHits = data(get("/api/v1/complaint-hits").param("keyword", suffix).cookie(manager2.cookies()));
        assertThat(crossTeamHits.at("/total").asLong()).isZero();

        mockMvc.perform(lead1.withAuth(post("/api/v1/complaints/999999/assign"))
                        .header("Idempotency-Key", "missing-case-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "不存在"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(lead1.withAuth(post("/api/v1/complaint-hits/" + hitId + "/dismiss"))
                        .header("Idempotency-Key", "stale-hit-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 8, "reason", "旧版本"))))
                .andExpect(status().isConflict());

        JsonNode confirmed = data(lead1.withAuth(post("/api/v1/complaint-hits/" + hitId + "/confirm"))
                .header("Idempotency-Key", "confirm-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "人工核实为正式客诉"))));
        long caseId = confirmed.at("/complaintCase/id").asLong();
        assertThat(confirmed.at("/complaintCase/status").asText()).isEqualTo("OPEN");
        assertThat(confirmed.at("/complaintCase/recipientUserIdSnapshot").asLong()).isEqualTo(4);
        JsonNode confirmReplay = data(lead1.withAuth(post("/api/v1/complaint-hits/" + hitId + "/confirm"))
                .header("Idempotency-Key", "confirm-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 0, "reason", "人工核实为正式客诉"))));
        assertThat(confirmReplay.at("/complaintCase/id").asLong()).isEqualTo(caseId);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_case WHERE tenant_id=1 AND complaint_hit_id=?", hitId)).isEqualTo(1);
        String caseNo = confirmed.at("/complaintCase/complaintCaseNo").asText();
        JsonNode scopedCases = data(get("/api/v1/complaints").param("keyword", caseNo).cookie(lead1.cookies()));
        assertThat(scopedCases.at("/total").asLong()).isEqualTo(1);
        JsonNode caseDetail = data(get("/api/v1/complaints/" + caseId).cookie(lead1.cookies()));
        assertThat(caseDetail.at("/empathyScript").asText()).contains("专人");
        mockMvc.perform(get("/api/v1/complaints/" + caseId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());

        mockMvc.perform(lead1.withAuth(post("/api/v1/complaints/" + caseId + "/resolve"))
                        .header("Idempotency-Key", "illegal-resolve-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "resolution", "非法跳转"))))
                .andExpect(status().isConflict());
        JsonNode assigned = data(lead1.withAuth(post("/api/v1/complaints/" + caseId + "/assign"))
                .header("Idempotency-Key", "assign-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "主管认领核查"))));
        assertThat(assigned.at("/complaintCase/status").asText()).isEqualTo("ASSIGNED");
        assertThat(assigned.at("/complaintCase/assigneeUserId").asLong()).isEqualTo(4);
        JsonNode resolved = data(lead1.withAuth(post("/api/v1/complaints/" + caseId + "/resolve"))
                .header("Idempotency-Key", "resolve-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "resolution", "已联系客户并完成服务复核"))));
        assertThat(resolved.at("/complaintCase/status").asText()).isEqualTo("RESOLVED");
        JsonNode closed = data(lead1.withAuth(post("/api/v1/complaints/" + caseId + "/close"))
                .header("Idempotency-Key", "close-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 2, "reason", "主管复核关闭"))));
        assertThat(closed.at("/complaintCase/status").asText()).isEqualTo("CLOSED");
        assertThat(closed.at("/history").size()).isEqualTo(4);
        mockMvc.perform(lead1.withAuth(post("/api/v1/complaints/" + caseId + "/dismiss"))
                        .header("Idempotency-Key", "closed-dismiss-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 3, "reason", "终态不可驳回"))))
                .andExpect(status().isConflict());
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_complaint_case_history SET reason_masked='非法覆盖' "
                + "WHERE tenant_id=1 AND complaint_case_id=?", caseId)).hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_complaint_hit_history "
                + "WHERE tenant_id=1 AND complaint_hit_id=?", hitId)).hasMessageContaining("append only");
    }

    @Test
    void nonTerminalCaseCanBeDismissedAndCrossTenantOrInstitutionAccessIsRejected() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies institution = login("inst1");
        String suffix = suffix();
        long otherTenantCustomer = newCustomer(2, 1, null, "IT-COMPLAINT-OTHER-TENANT-" + suffix);
        long otherFollowup = newFollowup(otherTenantCustomer, 1, "其他租户投诉" + suffix);
        mockMvc.perform(admin.withAuth(post("/api/v1/complaint-detection/followups/" + otherFollowup))
                        .header("Idempotency-Key", "cross-tenant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/complaint-hits").cookie(institution.cookies()))
                .andExpect(status().isForbidden());

        long customerId = newCustomer(1L, 2L, 1L, "IT-COMPLAINT-DISMISS-" + suffix);
        long hitId = newHit(customerId, 2, 1, 4, "IT-COMPLAINT-DISMISS-HIT-" + suffix);
        JsonNode confirmed = data(admin.withAuth(post("/api/v1/complaint-hits/" + hitId + "/confirm"))
                .header("Idempotency-Key", "dismiss-confirm-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "人工确认"))));
        long caseId = confirmed.at("/complaintCase/id").asLong();
        data(admin.withAuth(post("/api/v1/complaints/" + caseId + "/assign"))
                .header("Idempotency-Key", "dismiss-assign-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "开始处理"))));
        JsonNode dismissed = data(admin.withAuth(post("/api/v1/complaints/" + caseId + "/dismiss"))
                .header("Idempotency-Key", "dismiss-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 1, "reason", "核查后不构成客诉"))));
        assertThat(dismissed.at("/complaintCase/status").asText()).isEqualTo("DISMISSED");
        assertThat(dismissed.at("/history").size()).isEqualTo(3);
    }

    @Test
    void empathyScriptLibrarySupportsMultipleAuditedTemplatesAndCaseSelectionData() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix();
        String firstTitle = "首次响应-" + suffix;
        String secondTitle = "进度跟进-" + suffix;
        String firstScript = "我们理解您的感受，会立即安排专人核实。";
        String secondScript = "感谢您的耐心等待，当前已有专人跟进，我们会持续同步处理进度。";
        String createPath = "/api/v1/complaint-automation/empathy-scripts";

        mockMvc.perform(post(createPath).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", firstTitle, "script", firstScript, "reason", "新增话术"))))
                .andExpect(status().isForbidden());
        JsonNode first = data(admin.withAuth(post(createPath))
                .header("Idempotency-Key", "empathy-create-first-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", firstTitle, "script", firstScript, "reason", "新增首次响应话术"))));
        long firstId = first.at("/id").asLong();
        assertThat(first.at("/status").asText()).isEqualTo("ACTIVE");
        assertThat(first.at("/script").asText()).isEqualTo(firstScript);

        JsonNode second = data(admin.withAuth(post(createPath))
                .header("Idempotency-Key", "empathy-create-second-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", secondTitle, "script", secondScript, "reason", "新增进度跟进话术"))));
        long secondId = second.at("/id").asLong();
        JsonNode replay = data(admin.withAuth(post(createPath))
                .header("Idempotency-Key", "empathy-create-second-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", secondTitle, "script", secondScript, "reason", "新增进度跟进话术"))));
        assertThat(replay.at("/id").asLong()).isEqualTo(secondId);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_complaint_empathy_script WHERE tenant_id=1 AND title=?",
                secondTitle)).isEqualTo(1);

        mockMvc.perform(admin.withAuth(post(createPath))
                        .header("Idempotency-Key", "empathy-duplicate-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", secondTitle, "script", "不同内容", "reason", "重复标题"))))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(patch(createPath + "/" + secondId))
                        .header("Idempotency-Key", "empathy-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", secondTitle, "script", secondScript, "version", 99,
                                "reason", "错误版本"))))
                .andExpect(status().isConflict());
        JsonNode updated = data(admin.withAuth(patch(createPath + "/" + secondId))
                .header("Idempotency-Key", "empathy-update-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", secondTitle, "script", secondScript + "请及时查收。", "version", 0,
                        "reason", "补充进度提醒"))));
        assertThat(updated.at("/version").asLong()).isEqualTo(1);

        long customerId = newCustomer(1L, 2L, 1L, "IT-COMPLAINT-SCRIPTS-" + suffix);
        long hitId = newHit(customerId, 2, 1, 4, "IT-COMPLAINT-SCRIPTS-HIT-" + suffix);
        JsonNode detail = data(admin.withAuth(post("/api/v1/complaint-hits/" + hitId + "/confirm"))
                .header("Idempotency-Key", "empathy-confirm-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 0, "reason", "人工确认"))));
        assertThat(detail.at("/empathyScript").asText()).contains("专人核实");
        assertThat(detail.at("/empathyScripts").toString()).contains(firstTitle, secondTitle, "请及时查收");

        JsonNode disabled = data(admin.withAuth(post(createPath + "/" + secondId + "/disable"))
                .header("Idempotency-Key", "empathy-disable-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 1, "reason", "停用旧话术"))));
        assertThat(disabled.at("/status").asText()).isEqualTo("DISABLED");
        JsonNode disableReplay = data(admin.withAuth(post(createPath + "/" + secondId + "/disable"))
                .header("Idempotency-Key", "empathy-disable-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("version", 1, "reason", "停用旧话术"))));
        assertThat(disableReplay.at("/version").asLong()).isEqualTo(2);
        JsonNode afterDisable = data(get("/api/v1/complaints/" + detail.at("/complaintCase/id").asLong())
                .cookie(admin.cookies()));
        assertThat(afterDisable.at("/empathyScripts").toString()).contains(firstTitle).doesNotContain(secondTitle);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND target_type='COMPLAINT_EMPATHY_SCRIPT' AND target_id=?", Long.toString(secondId))).isEqualTo(3);

        jdbcTemplate.update("INSERT INTO crm_complaint_empathy_script (tenant_id,title,script) VALUES (2,?,?)",
                "其他租户-" + suffix, "其他租户话术");
        long otherTenantId = queryLong("SELECT id FROM crm_complaint_empathy_script WHERE tenant_id=2 AND title=?",
                "其他租户-" + suffix);
        mockMvc.perform(admin.withAuth(patch(createPath + "/" + otherTenantId))
                        .header("Idempotency-Key", "empathy-cross-tenant-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "越权修改", "script", "越权内容", "version", 0,
                                "reason", "越权测试"))))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_complaint_empathy_script WHERE id=?", secondId))
                .hasMessageContaining("cannot be deleted");
        assertThat(firstId).isPositive();
    }

    private long newCustomer(long tenantId, long ownerId, Long teamId, String customerNo) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (?,?,?,?,'合成客诉客户')", tenantId, ownerId, teamId, customerNo);
        return queryLong("SELECT id FROM crm_customer WHERE tenant_id=? AND customer_no=?", tenantId, customerNo);
    }

    private long newFollowup(long customerId, long ownerId, String summary) {
        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,summary,created_by) "
                + "SELECT tenant_id,id,owner_staff_id,'PHONE',?,owner_staff_id FROM crm_customer WHERE id=?", summary, customerId);
        return queryLong("SELECT f.id FROM crm_followup f JOIN crm_customer c ON c.tenant_id=f.tenant_id "
                + "AND c.id=f.customer_id WHERE c.id=? ORDER BY f.id DESC LIMIT 1", customerId);
    }

    private long newHit(long customerId, long ownerId, long teamId, long recipientId, String sourceId) {
        jdbcTemplate.update("INSERT INTO crm_complaint_hit (tenant_id,customer_id,owner_staff_id_snapshot,team_id_snapshot,"
                        + "source_type,source_id,keyword_snapshot,evidence_masked,routing_status,recipient_user_id_snapshot,"
                        + "recipient_source_snapshot,policy_version_snapshot) VALUES (1,?,?,?,'FOLLOWUP',?,'投诉','脱敏证据',"
                        + "'ROUTED',?,'TEAM_LEADER',0)", customerId, ownerId, teamId, sourceId, recipientId);
        return queryLong("SELECT id FROM crm_complaint_hit WHERE tenant_id=1 AND source_id=?", sourceId);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andExpect(status().is2xxSuccessful())
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
}
