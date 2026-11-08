package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskActionWorkflowApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fourActionsAreAtomicAuditedAndKeepOriginalAssignee() throws Exception {
        AuthCookies manager = login("manager1");
        AuthCookies lead = login("lead1");
        long taskId = createTask(manager, "ITT-V18-完整流转");

        action(manager, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":0}", "claim-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(2))
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.taskTeamId").value(1))
                .andExpect(jsonPath("$.data.claimedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(1));

        action(lead, post("/api/v1/tasks/" + taskId + "/reassign"),
                "{\"assigneeUserId\":4,\"reason\":\"主管调整\",\"version\":1}", "reassign-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(4))
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.taskTeamId").value(1))
                .andExpect(jsonPath("$.data.version").value(2));

        action(lead, post("/api/v1/tasks/" + taskId + "/return"),
                "{\"reason\":\"退回任务池\",\"version\":2}", "return-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.assigneeUserId").doesNotExist())
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.taskTeamId").value(1))
                .andExpect(jsonPath("$.data.claimedAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(3));

        action(lead, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":3}", "claim-b")
                .andExpect(status().isOk());
        action(lead, post("/api/v1/tasks/" + taskId + "/complete"), "{\"version\":4}", "complete-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(4))
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.version").value(5));

        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].action").value("CLAIM"))
                .andExpect(jsonPath("$.data[1].action").value("REASSIGN"))
                .andExpect(jsonPath("$.data[1].reason").value("主管调整"))
                .andExpect(jsonPath("$.data[1].toAssigneeUserId").value(4))
                .andExpect(jsonPath("$.data[2].action").value("RETURN"))
                .andExpect(jsonPath("$.data[3].action").value("CLAIM"))
                .andExpect(jsonPath("$.data[4].action").value("COMPLETE"));
        Long logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_task_action_log WHERE tenant_id=1 AND task_id=?", Long.class, taskId);
        Long auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND target_type='TASK' AND target_id=? "
                        + "AND action_code IN ('TASK_CLAIM','TASK_REASSIGN','TASK_RETURN','TASK_COMPLETE')",
                Long.class, Long.toString(taskId));
        assertThat(logCount).isEqualTo(5);
        assertThat(auditCount).isEqualTo(5);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_task SET original_assignee_user_id=4 WHERE tenant_id=1 AND id=?", taskId))
                .hasMessageContaining("Task original assignee is immutable");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE crm_task_action_log SET reason='篡改' WHERE tenant_id=1 AND task_id=?", taskId))
                .hasMessageContaining("Task action log is append only");

        mockMvc.perform(lead.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\",\"version\":5}"))
                .andExpect(status().isBadRequest());
        action(lead, post("/api/v1/tasks/" + taskId + "/complete"), "{\"version\":5}", "complete-terminal")
                .andExpect(status().isConflict());
    }

    @Test
    void poolUsesTaskTeamAndRoleRulesSurviveReassignment() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies lead2 = login("lead2");
        AuthCookies admin = login("admin");
        long taskId = createTask(manager1, "ITT-V18-池权限");

        action(manager2, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":0}", "cross-claim")
                .andExpect(status().isNotFound());
        action(manager1, post("/api/v1/tasks/" + taskId + "/reassign"),
                "{\"assigneeUserId\":2,\"reason\":\"普通管家越权\",\"version\":0}", "manager-reassign")
                .andExpect(status().isForbidden());
        action(lead1, post("/api/v1/tasks/" + taskId + "/reassign"),
                "{\"assigneeUserId\":3,\"reason\":\"主管跨组\",\"version\":0}", "lead-cross-team")
                .andExpect(status().isForbidden());
        action(admin, post("/api/v1/tasks/" + taskId + "/reassign"),
                "{\"assigneeUserId\":3,\"reason\":\"管理员跨组\",\"version\":0}", "admin-cross-team")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.assigneeUserId").value(3))
                .andExpect(jsonPath("$.data.taskTeamId").value(2));

        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(lead1.cookies()))
                .andExpect(status().isNotFound());
        action(manager2, post("/api/v1/tasks/" + taskId + "/return"),
                "{\"reason\":\"本人退回\",\"version\":1}", "manager-return")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskTeamId").value(2));
        mockMvc.perform(get("/api/v1/tasks").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT-V18-池权限')]").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks").cookie(lead2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT-V18-池权限')]").isNotEmpty());
        action(lead2, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":2}", "lead2-claim")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeUserId").value(9));
        action(manager2, post("/api/v1/tasks/" + taskId + "/complete"), "{\"version\":3}", "not-owner-complete")
                .andExpect(status().isNotFound());
    }

    @Test
    void returnedPoolIsVisibleAndClaimableBySameTeamSelfManagerOnly() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies sameTeamManager = createSameTeamSelfManager();
        AuthCookies manager2 = login("manager2");
        long taskId = createTask(manager1, "ITT-V18-同组退回池");

        action(manager1, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":0}", "pool-self-claim")
                .andExpect(status().isOk());
        action(manager1, post("/api/v1/tasks/" + taskId + "/return"),
                "{\"reason\":\"普通管家退回\",\"version\":1}", "pool-self-return")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.assigneeUserId").doesNotExist())
                .andExpect(jsonPath("$.data.taskTeamId").value(1));

        mockMvc.perform(get("/api/v1/tasks").cookie(sameTeamManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == " + taskId + ")]").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.id == " + taskId + ")]").isEmpty());
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        action(manager2, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":2}", "pool-cross-claim")
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(sameTeamManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].action").value("CLAIM"))
                .andExpect(jsonPath("$.data[0].fromStatus").value("OPEN"))
                .andExpect(jsonPath("$.data[0].toStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data[1].action").value("RETURN"))
                .andExpect(jsonPath("$.data[1].fromStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data[1].toStatus").value("OPEN"));
        action(sameTeamManager, post("/api/v1/tasks/" + taskId + "/claim"),
                "{\"version\":2}", "pool-same-team-claim")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(sameTeamManagerId()))
                .andExpect(jsonPath("$.data.taskTeamId").value(1));
    }

    @Test
    void actionsRequireCsrfVersionAndIdempotencyAndReplayWithoutDuplicateLogs() throws Exception {
        AuthCookies manager = login("manager1");
        long taskId = createTask(manager, "ITT-V18-幂等");

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/claim").cookie(manager.cookies())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")
                        .header("Idempotency-Key", "no-csrf"))
                .andExpect(status().isForbidden());
        action(manager, post("/api/v1/tasks/" + taskId + "/claim"), "{}", "missing-version")
                .andExpect(status().isBadRequest());
        mockMvc.perform(manager.withAuth(post("/api/v1/tasks/" + taskId + "/claim"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());

        action(manager, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":0}", "same-claim-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        action(manager, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":0}", "same-claim-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        action(manager, post("/api/v1/tasks/" + taskId + "/claim"), "{\"version\":1}", "same-claim-key")
                .andExpect(status().isConflict());
        action(manager, post("/api/v1/tasks/" + taskId + "/complete"), "{\"version\":0}", "stale-version")
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void legacyAssigneeUsesReassignAndRequiresReason() throws Exception {
        AuthCookies lead = login("lead1");
        AuthCookies manager = login("manager1");
        long taskId = createTask(manager, "ITT-V18-旧转派");

        action(lead, patch("/api/v1/tasks/" + taskId + "/assignee"),
                "{\"assigneeUserId\":4,\"version\":0}", "legacy-no-reason")
                .andExpect(status().isBadRequest());
        action(lead, patch("/api/v1/tasks/" + taskId + "/assignee"),
                "{\"assigneeUserId\":4,\"reason\":\"旧入口转派\",\"version\":0}", "legacy-reassign")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assigneeUserId").value(4))
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.claimedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks/" + taskId + "/actions").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].action").value("REASSIGN"))
                .andExpect(jsonPath("$.data[0].reason").value("旧入口转派"));
    }

    private AuthCookies createSameTeamSelfManager() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO sys_user (tenant_id, org_id, team_id, username, display_name,
                                      password_hash, user_type, status)
                SELECT tenant_id, org_id, team_id, 'it_task_pool_peer', '同组池测试管家',
                       password_hash, user_type, status
                FROM sys_user WHERE tenant_id=1 AND username='manager1'
                """);
        long userId = sameTeamManagerId();
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (tenant_id, user_id, role_id)
                SELECT tenant_id, ?, role_id FROM sys_user_role WHERE tenant_id=1 AND user_id=2
                """, userId);
        jdbcTemplate.update("""
                INSERT INTO sys_data_scope (tenant_id, user_id, resource, scope_type, scope_ref_id)
                SELECT tenant_id, ?, resource, scope_type, scope_ref_id
                FROM sys_data_scope WHERE tenant_id=1 AND user_id=2 AND resource='customer'
                """, userId);
        return login("it_task_pool_peer");
    }

    private long sameTeamManagerId() {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=1 AND username='it_task_pool_peer'", Long.class);
        return userId == null ? 0 : userId;
    }

    private long createTask(AuthCookies actor, String title) throws Exception {
        String created = mockMvc.perform(actor.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"FOLLOWUP\",\"title\":\"" + title + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalAssigneeUserId").value(2))
                .andExpect(jsonPath("$.data.taskTeamId").value(1))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).at("/data/id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions action(
            AuthCookies actor, MockHttpServletRequestBuilder builder, String body, String key) throws Exception {
        return mockMvc.perform(actor.withAuth(builder).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key));
    }
}
