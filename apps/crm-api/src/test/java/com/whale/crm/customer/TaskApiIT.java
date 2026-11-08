package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 任务：assigneeId 只收窄；通用PATCH禁止状态流转；同组主管可改；范围隔离。
 */
class TaskApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createAssignsSelf_andAssigneeIdOnlyNarrows() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies admin = login("admin");

        long customerId = createCustomer(manager1, "ITT-任务");
        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\","
                                + "\"title\":\"ITT 回访\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.assigneeUserId").value(2))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"));

        // SELF：manager1 可见本人任务；manager2 不可见；manager2 传 assigneeId=2 仍空（收窄不放大）
        mockMvc.perform(get("/api/v1/tasks?customerId=").cookie(manager1.cookies()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/tasks").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT 回访')]").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT 回访')]").isEmpty());
        mockMvc.perform(get("/api/v1/tasks?assigneeId=2").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT 回访')]").isEmpty());

        // TEAM：lead1 可见组内成员任务；ALL：admin 可见
        mockMvc.perform(get("/api/v1/tasks").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT 回访')]").isNotEmpty());
        mockMvc.perform(get("/api/v1/tasks?assigneeId=2").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.title == 'ITT 回访')]").isNotEmpty());

        // 挂不可见客户 → 404
        long adminCustomer = createCustomer(admin, "ITT-管理员客户", 3L);
        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + adminCustomer + ",\"taskType\":\"FOLLOWUP\",\"title\":\"越组\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void genericPatchRejectsStatus_andTerminalActionsRejectFurtherTransitions() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");
        AuthCookies manager2 = login("manager2");

        String created = mockMvc.perform(manager1.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"FOLLOWUP\",\"title\":\"ITT 状态机\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(created).at("/data/id").asLong();

        // 他人（非本组）改 → 404（不可见）
        mockMvc.perform(manager2.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":0}"))
                .andExpect(status().isNotFound());

        // 同组主管改标题（不改状态）
        mockMvc.perform(lead1.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ITT 状态机-主管改\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(manager1.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"version\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks/" + taskId + "/claim"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-status-claim")
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks/" + taskId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-status-complete")
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));

        mockMvc.perform(manager1.withAuth(post("/api/v1/tasks/" + taskId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-status-terminal")
                        .content("{\"version\":3}"))
                .andExpect(status().isConflict());
        mockMvc.perform(manager1.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELED\",\"version\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void processingAssigneeAndClaimFollowRoleAndTerminalRules() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        String created = mockMvc.perform(manager1.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskType\":\"FOLLOWUP\",\"title\":\"ITT 接取与负责人\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(created).at("/data/id").asLong();

        mockMvc.perform(manager1.withAuth(patch("/api/v1/tasks/" + taskId + "/assignee"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-manager-reassign")
                        .content("{\"assigneeUserId\":3,\"reason\":\"越权转派\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(lead1.withAuth(patch("/api/v1/tasks/" + taskId + "/assignee"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-lead-reassign")
                        .content("{\"assigneeUserId\":4,\"reason\":\"主管转派\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assigneeUserId").value(4))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(lead1.withAuth(patch("/api/v1/tasks/" + taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":\"URGENT\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value("URGENT"));
        mockMvc.perform(lead1.withAuth(post("/api/v1/tasks/" + taskId + "/complete"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-lead-complete")
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));
        mockMvc.perform(lead1.withAuth(patch("/api/v1/tasks/" + taskId + "/assignee"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "legacy-terminal-reassign")
                        .content("{\"assigneeUserId\":2,\"reason\":\"终态转派\",\"version\":3}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/tasks?status=PROCESSING").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void dashboardDutySummaryUsesRealDueDatesAndOwnerScope() throws Exception {
        AuthCookies admin = login("admin");
        String username = "it_duty_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"值班测试管家\","
                                + "\"password\":\"" + DEV_PASSWORD + "\",\"userType\":\"EMPLOYEE\",\"roleIds\":[4]}"))
                .andExpect(status().isCreated());
        jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id) "
                + "SELECT 1,id,'customer','SELF',NULL FROM sys_user WHERE tenant_id=1 AND username=?", username);
        AuthCookies manager = login(username);
        long customerId = createCustomer(manager, "ITT-值班指标");
        mockMvc.perform(manager.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\","
                                + "\"title\":\"ITT-唯一逾期回访\",\"priority\":\"HIGH\",\"dueAt\":\""
                                + Instant.now().minusSeconds(3600) + "\"}"))
                .andExpect(status().isCreated());

        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        mockMvc.perform(get("/api/v1/reports/operations-dashboard")
                        .param("from", today).param("to", today).param("timeZone", "Asia/Shanghai")
                        .cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duty.newCustomerCount").value(1))
                .andExpect(jsonPath("$.data.duty.pendingTaskCount").value(1))
                .andExpect(jsonPath("$.data.duty.dueFollowupCount").value(1))
                .andExpect(jsonPath("$.data.duty.overdueTaskCount").value(1))
                .andExpect(jsonPath("$.data.duty.pendingAppointmentCount").value(0))
                .andExpect(jsonPath("$.data.duty.awaitingRedemptionCount").value(0))
                .andExpect(jsonPath("$.data.duty.pendingRefundCount").value(0));
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        return createCustomer(actor, nickname, null);
    }

    private long createCustomer(AuthCookies actor, String nickname, Long ownerId) throws Exception {
        String owner = ownerId == null ? "" : ",\"ownerStaffId\":" + ownerId;
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"" + owner + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
