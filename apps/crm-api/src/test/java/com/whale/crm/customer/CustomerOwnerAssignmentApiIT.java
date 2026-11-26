package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 主管直接分配客户（单客/批量）+ 归属日志/转交单分页接口集成测试（真实 MySQL 8.4 + RBAC + CSRF）。
 */
class CustomerOwnerAssignmentApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void leadAssignsCustomerFromButlerBToButlerC_immediateEffect_historyAuditTimelineAndIdempotency() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");

        // 在管家一组（team 1）新建在职管家 C，模拟“把 A 客户由 B 管家（manager1）分给 C 管家”
        String butlerCUsername = "butlerc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long butlerCId = createTeam1Butler(admin, butlerCUsername, "周小洁");
        AuthCookies butlerC = login(butlerCUsername);

        long customerId = createCustomer(manager1, "IT分配-A客户", "13911112222");

        // 普通管家无 customer:transfer → 403
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .header("Idempotency-Key", "idem-forbid-" + customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":" + butlerCId + ",\"reason\":\"普通管家越权\",\"version\":0}"))
                .andExpect(status().isForbidden());

        // 管家主管（lead1）直接把 A 客户由 B 管家（manager1=2）分配给 C 管家（butlerCId）→ 立即生效
        String idemKey = "idem-assign-" + UUID.randomUUID();
        String assignBody = "{\"newOwnerId\":" + butlerCId + ",\"reason\":\"王丽休假，客户交由周小洁跟进\",\"version\":0}";
        String assigned = mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customerId))
                .andExpect(jsonPath("$.data.oldOwnerId").value(2))
                .andExpect(jsonPath("$.data.oldOwnerName").value("王丽"))
                .andExpect(jsonPath("$.data.newOwnerId").value(butlerCId))
                .andExpect(jsonPath("$.data.newOwnerName").value("周小洁"))
                .andExpect(jsonPath("$.data.changedBy").value(4))
                .andExpect(jsonPath("$.data.approvalRef").value("DIRECT_ASSIGN"))
                .andExpect(jsonPath("$.data.customerVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        long historyId = objectMapper.readTree(assigned).at("/data/historyId").asLong();

        // 立即生效：原管家 B（SELF）失去可见性 404；新管家 C（SELF）立即可见 200
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(butlerC.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(butlerCId))
                .andExpect(jsonPath("$.data.ownerName").value("周小洁"))
                .andExpect(jsonPath("$.data.teamId").value(1))
                .andExpect(jsonPath("$.data.version").value(1));

        // 主管按 ownerId 筛选：在 C 名下可见，在 B 名下不再出现
        mockMvc.perform(get("/api/v1/customers")
                        .param("ownerId", Long.toString(butlerCId))
                        .param("keyword", "IT分配-A客户")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(customerId));
        mockMvc.perform(get("/api/v1/customers")
                        .param("ownerId", "2")
                        .param("keyword", "IT分配-A客户")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 归属历史（单客 + 全局分页 + 时间线）与审计完整且无明文手机号泄露
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/ownership-history").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(historyId))
                .andExpect(jsonPath("$.data[0].oldOwnerId").value(2))
                .andExpect(jsonPath("$.data[0].newOwnerId").value(butlerCId))
                .andExpect(jsonPath("$.data[0].approvalRef").value("DIRECT_ASSIGN"))
                .andExpect(jsonPath("$.data[0].changeMode").value("DIRECT_ASSIGN"));

        mockMvc.perform(get("/api/v1/customer-ownership-history")
                        .param("customerId", Long.toString(customerId))
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].changeMode").value("DIRECT_ASSIGN"))
                .andExpect(jsonPath("$.data.items[0].oldOwnerName").value("王丽"))
                .andExpect(jsonPath("$.data.items[0].newOwnerName").value("周小洁"));

        // team 2 主管看不到 team 1 客户的归属日志
        mockMvc.perform(get("/api/v1/customer-ownership-history")
                        .param("customerId", Long.toString(customerId))
                        .cookie(login("lead2").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 统一时间线自动包含本次直接分配事件
        String timeline = mockMvc.perform(get("/api/v1/customers/" + customerId + "/timeline").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(timeline).contains("\"type\":\"OWNERSHIP\"").contains("周小洁").doesNotContain("13911112222");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='CUSTOMER_OWNER_ASSIGN' AND target_id=?",
                Integer.class, Long.toString(customerId));
        assertThat(auditCount).isEqualTo(1);
        String afterJson = jdbcTemplate.queryForObject(
                "SELECT after_json FROM crm_audit_log WHERE action_code='CUSTOMER_OWNER_ASSIGN' AND target_id=?",
                String.class, Long.toString(customerId));
        JsonNode afterNode = objectMapper.readTree(afterJson);
        assertThat(afterNode.path("mode").asText()).isEqualTo("DIRECT_ASSIGN");
        assertThat(afterNode.path("ownerStaffId").asLong()).isEqualTo(butlerCId);
        assertThat(afterJson).doesNotContain("13911112222");

        // 同幂等键重放返回原结果，不重复写历史或报版本冲突；异体同键 409
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.historyId").value(historyId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE customer_id=?", Integer.class, customerId))
                .isEqualTo(1);

        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"篡改请求体\",\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTeamBoundaryAndConflicts_enforcedStrictly() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies lead2 = login("lead2");
        AuthCookies admin = login("admin");

        long customerId = createCustomer(manager1, "IT分配-边界校验", null);

        // 非管家目标（finance1=5）→ 400
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":5,\"reason\":\"非管家\",\"version\":0}"))
                .andExpect(status().isBadRequest());

        // lead1 跨组分配给 team 2 的 manager2(3) → 403
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":3,\"reason\":\"跨组\",\"version\":0}"))
                .andExpect(status().isForbidden());

        // lead2（team 2 主管）操作 team 1 客户 → 404（不泄露客户存在性）
        mockMvc.perform(lead2.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":9,\"reason\":\"跨组偷改\",\"version\":0}"))
                .andExpect(status().isNotFound());

        // 分配给当前原管家 → 409
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":2,\"reason\":\"同管家\",\"version\":0}"))
                .andExpect(status().isConflict());

        // 存在 PENDING 转交单时禁止直接分配 → 409
        String transferBody = mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"先提转交单\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(transferBody).at("/data/id").asLong();

        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"试图绕过待审批单\",\"version\":0}"))
                .andExpect(status().isConflict());

        // 范围分页查转交单：lead1 可见，lead2 不可见
        mockMvc.perform(get("/api/v1/customer-ownership-transfers")
                        .param("status", "PENDING")
                        .param("keyword", "IT分配-边界校验")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(transferId));
        mockMvc.perform(get("/api/v1/customer-ownership-transfers")
                        .param("status", "PENDING")
                        .param("keyword", "IT分配-边界校验")
                        .cookie(lead2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 驳回待审批单后修改客户资料（version 0→1），旧版本 0 分配报 409，带新版本 1 由 ADMIN 跨组分配成功
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"改走管理员跨组分配\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"先更新备注\",\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":3,\"reason\":\"旧版本\",\"version\":0}"))
                .andExpect(status().isConflict());

        mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/owner-assignment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":3,\"reason\":\"管理员跨组分配\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newOwnerId").value(3))
                .andExpect(jsonPath("$.data.newTeamId").value(2))
                .andExpect(jsonPath("$.data.customerVersion").value(2));

        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(3))
                .andExpect(jsonPath("$.data.teamId").value(2));
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchAssign_handlesMixedSkipIdempotentReplayAndAtomicRollback() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");

        long c1 = createCustomer(manager1, "IT批量-客户1", null);
        long c2 = createCustomer(manager1, "IT批量-客户2", null);
        long c3 = createCustomer(lead1, "IT批量-客户3已在目标名下", null);

        // 普通管家批量分配 → 403
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/owner-assignments/batch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"customerId\":" + c1 + ",\"version\":0}],\"newOwnerId\":4,\"reason\":\"越权\"}"))
                .andExpect(status().isForbidden());

        // 批量分配：[c1(manager1), c2(manager1), c3(lead1)] → lead1(4)
        String batchKey = "idem-batch-" + UUID.randomUUID();
        String batchPayload = "{\"items\":["
                + "{\"customerId\":" + c1 + ",\"version\":0},"
                + "{\"customerId\":" + c2 + ",\"version\":0},"
                + "{\"customerId\":" + c3 + ",\"version\":0}"
                + "],\"newOwnerId\":4,\"reason\":\"批量均衡分配\"}";
        String batchResp = mockMvc.perform(lead1.withAuth(post("/api/v1/customers/owner-assignments/batch"))
                        .header("Idempotency-Key", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.assignedCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.items[1].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.items[2].status").value("SKIPPED"))
                .andReturn().getResponse().getContentAsString();
        String batchRef = objectMapper.readTree(batchResp).at("/data/batchRef").asText();
        assertThat(batchRef).startsWith("BATCH-");

        // c1/c2 生效且归属历史 changeMode=BATCH_ASSIGN；c3 未被写多余历史
        mockMvc.perform(get("/api/v1/customers/" + c1 + "/ownership-history").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].approvalRef").value(batchRef))
                .andExpect(jsonPath("$.data[0].changeMode").value("BATCH_ASSIGN"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE customer_id=?", Integer.class, c3))
                .isZero();

        // 同幂等键重放返回同一 batchRef，不重复入库
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/owner-assignments/batch"))
                        .header("Idempotency-Key", batchKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batchRef").value(batchRef))
                .andExpect(jsonPath("$.data.assignedCount").value(2))
                .andExpect(jsonPath("$.data.skippedCount").value(1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE approval_ref=?", Integer.class, batchRef))
                .isEqualTo(2);

        // 原子回滚：c4 版本正确 + c5 版本过期 → 409 且 c4 归属保持 manager1 不变
        long c4 = createCustomer(manager1, "IT批量回滚-正常", null);
        long c5 = createCustomer(manager1, "IT批量回滚-冲突", null);
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/owner-assignments/batch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"customerId\":" + c4 + ",\"version\":0},"
                                + "{\"customerId\":" + c5 + ",\"version\":99}],\"newOwnerId\":4,\"reason\":\"应整批回滚\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/customers/" + c4).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE customer_id=?", Integer.class, c4))
                .isZero();
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile) throws Exception {
        String mobileJson = mobile == null ? "" : ",\"mobile\":\"" + mobile + "\"";
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"" + mobileJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createTeam1Butler(AuthCookies admin, String username, String displayName) throws Exception {
        String rolesJson = mockMvc.perform(get("/api/v1/roles").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long managerRoleId = -1;
        for (JsonNode role : objectMapper.readTree(rolesJson).at("/data")) {
            if ("MANAGER".equals(role.path("roleCode").asText())) {
                managerRoleId = role.path("id").asLong();
                break;
            }
        }
        assertThat(managerRoleId).isPositive();
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"" + displayName
                                + "\",\"password\":\"" + DEV_PASSWORD + "\",\"userType\":\"EMPLOYEE\",\"teamId\":1,"
                                + "\"roleIds\":[" + managerRoleId + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).at("/data/id").asLong();
    }
}
