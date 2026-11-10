package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跟进流水：创建刷新客户 last_followup_at；范围隔离；创建者/同组主管可改；乐观锁。
 */
class FollowupApiIT extends AbstractMysqlIT {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void createUpdatesLastFollowupAt_andScopedList() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");

        long customerId = createCustomer(manager1, "ITF-跟进");
        mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"首次沟通\",\"estimatedAmountFen\":19900,\"intentProject\":\"热玛吉\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andExpect(jsonPath("$.data.ownerStaffId").value(2))
                .andExpect(jsonPath("$.data.estimatedAmountFen").value(19900))
                .andExpect(jsonPath("$.data.contactAt").exists());

        // 客户最后跟进时间依真实跟进刷新
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastFollowupAt").exists());

        // 列表：manager1 可见；manager2 空页；指定他人客户 → 404
        mockMvc.perform(get("/api/v1/followups?customerId=" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].customerName").value("ITF-跟进"));
        mockMvc.perform(get("/api/v1/followups?customerId=" + customerId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/followups?keyword=").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.customerId == " + customerId + ")]").isEmpty());
    }

    @Test
    void automaticTaskFollowsNextFollowupAtWithoutDuplicatingOrReopening() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        long customerId = createCustomer(manager1, "ITF-自动回访");
        String created = mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"约定下次沟通\",\"intentProject\":\"光子嫩肤\","
                                + "\"nextFollowupAt\":\"2026-12-15T01:30:00Z\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(created).at("/data/id").asLong();
        String sourceRef = "FOLLOWUP:" + followupId;

        var task = jdbcTemplate.queryForMap("SELECT * FROM crm_task WHERE tenant_id=1 "
                + "AND source_type='FOLLOWUP' AND source_ref=?", sourceRef);
        long taskId = ((Number) task.get("id")).longValue();
        org.assertj.core.api.Assertions.assertThat(((Number) task.get("assignee_user_id")).longValue()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(((Number) task.get("task_team_id")).longValue()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(task.get("status")).isEqualTo("OPEN");
        org.assertj.core.api.Assertions.assertThat(task.get("title").toString()).contains("ITF-自动回访", "光子嫩肤");
        org.assertj.core.api.Assertions.assertThat(task.get("description")).isEqualTo("约定下次沟通");
        org.assertj.core.api.Assertions.assertThat(((java.time.LocalDateTime) task.get("due_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(java.time.Instant.parse("2026-12-15T01:30:00Z"));
        mockMvc.perform(get("/api/v1/tasks?assigneeId=2&status=OPEN").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.customerId == " + customerId + ")]").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.customerId == " + customerId + ")]").isEmpty());

        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"补充说明\",\"version\":0}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM crm_task WHERE tenant_id=1 AND id=?", Long.class, taskId)).isZero();

        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextFollowupAt\":\"2026-12-16T02:00:00Z\",\"version\":1}"))
                .andExpect(status().isOk());
        task = jdbcTemplate.queryForMap("SELECT * FROM crm_task WHERE tenant_id=1 AND id=?", taskId);
        org.assertj.core.api.Assertions.assertThat(((java.time.LocalDateTime) task.get("due_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(java.time.Instant.parse("2026-12-16T02:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(((Number) task.get("version")).longValue()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_task "
                + "WHERE tenant_id=1 AND source_type='FOLLOWUP' AND source_ref=?", Long.class, sourceRef)).isEqualTo(1);

        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks/" + taskId + "/claim"))
                        .header("Idempotency-Key", "auto-followup-claim-" + followupId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextFollowupAt\":\"2026-12-17T03:00:00Z\",\"version\":2}"))
                .andExpect(status().isOk());
        task = jdbcTemplate.queryForMap("SELECT * FROM crm_task WHERE tenant_id=1 AND id=?", taskId);
        org.assertj.core.api.Assertions.assertThat(task.get("status")).isEqualTo("PROCESSING");
        org.assertj.core.api.Assertions.assertThat(((java.time.LocalDateTime) task.get("due_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(java.time.Instant.parse("2026-12-16T02:00:00Z"));

        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks/" + taskId + "/complete"))
                        .header("Idempotency-Key", "auto-followup-complete-" + followupId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextFollowupAt\":\"2026-12-18T04:00:00Z\",\"version\":3}"))
                .andExpect(status().isOk());
        task = jdbcTemplate.queryForMap("SELECT * FROM crm_task WHERE tenant_id=1 AND id=?", taskId);
        org.assertj.core.api.Assertions.assertThat(task.get("status")).isEqualTo("DONE");
        org.assertj.core.api.Assertions.assertThat(((java.time.LocalDateTime) task.get("due_at")).toInstant(java.time.ZoneOffset.UTC))
                .isEqualTo(java.time.Instant.parse("2026-12-16T02:00:00Z"));
        org.assertj.core.api.Assertions.assertThat(((Number) task.get("version")).longValue()).isEqualTo(3);

        String noDate = mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"PHONE\",\"summary\":\"无需再次回访\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long noDateFollowupId = objectMapper.readTree(noDate).at("/data/id").asLong();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_task "
                + "WHERE tenant_id=1 AND source_type='FOLLOWUP' AND source_ref=?", Long.class,
                "FOLLOWUP:" + noDateFollowupId)).isZero();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO crm_task (tenant_id,customer_id,assignee_user_id,original_assignee_user_id,task_team_id,"
                        + "task_type,priority,status,title,description,due_at,source_type,source_ref) "
                        + "SELECT tenant_id,customer_id,assignee_user_id,original_assignee_user_id,task_team_id,"
                        + "task_type,priority,status,title,description,due_at,source_type,source_ref "
                        + "FROM crm_task WHERE tenant_id=1 AND id=?", taskId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void patch_creatorOrTeamLeadOnly_withOptimisticLock() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");

        long customerId = createCustomer(manager1, "ITF-修改");
        String created = mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"PHONE\",\"summary\":\"原始\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(created).at("/data/id").asLong();

        // 创建者可改
        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"更新\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("更新"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 同组主管可改
        mockMvc.perform(lead1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"主管批注\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));

        // 旧版本 → 409
        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"并发\",\"version\":1}"))
                .andExpect(status().isConflict());

        // 跨组管家 404（不可见）
        mockMvc.perform(manager2.withAuth(patch("/api/v1/followups/" + followupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"越权\",\"version\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteSoftDeletesAndKeepsAuditAndPermissions() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        long customerId = createCustomer(manager1, "ITF-软删除");
        String created = mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\",\"summary\":\"误录删除\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(created).at("/data/id").asLong();

        mockMvc.perform(manager2.withAuth(delete("/api/v1/followups/" + followupId).param("version", "0")))
                .andExpect(status().isNotFound());
        mockMvc.perform(manager1.withAuth(delete("/api/v1/followups/" + followupId).param("version", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        Integer deleted = jdbcTemplate.queryForObject("SELECT deleted FROM crm_followup WHERE tenant_id=1 AND id=?",
                Integer.class, followupId);
        org.assertj.core.api.Assertions.assertThat(deleted).isEqualTo(1);
        mockMvc.perform(get("/api/v1/followups?customerId=" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(manager1.withAuth(delete("/api/v1/followups/" + followupId).param("version", "1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/audit-logs?actionCode=FOLLOWUP_DELETE&targetType=FOLLOWUP")
                        .cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
