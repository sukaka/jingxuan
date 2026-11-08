package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C04 转交审批：MANAGER 无权转交 403；组内 MANAGER_LEAD 申请+批准生效且 history/审计完整；
 * 跨团队仅 ADMIN；baseVersion 失配 409；批准后原管家失去可见性。
 */
class CustomerTransferApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void c04_inTeamLeadTransferFlow_withHistoryAndAudit() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");

        long customerId = createCustomer(manager1, "ITC04-转交");

        // MANAGER 无 customer:transfer → 403
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"test\"}"))
                .andExpect(status().isForbidden());

        // lead1 申请：manager1(2) → lead1(4)，同组（team 1）
        String created = mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"休假代管\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.fromOwnerId").value(2))
                .andExpect(jsonPath("$.data.toOwnerId").value(4))
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(created).at("/data/id").asLong();
        long transferVersion = objectMapper.readTree(created).at("/data/version").asLong();
        long baseVersion = objectMapper.readTree(created).at("/data/baseVersion").asLong();

        // 申请未生效前归属不变
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(2));

        // 有 PENDING 单时重复申请 → 409
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"重复\"}"))
                .andExpect(status().isConflict());

        // 审批通过 → EFFECTIVE，同事务写归属
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + transferVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EFFECTIVE"))
                .andExpect(jsonPath("$.data.reviewedBy").value(4));

        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(4))
                .andExpect(jsonPath("$.data.teamId").value(1))
                .andExpect(jsonPath("$.data.version").value(baseVersion + 1));

        // 原管家（SELF 范围）失去可见性 → 404
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound());

        // 归属历史不可覆盖日志：old/new/changedBy/reason/effective_at 完整
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/ownership-history").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].oldOwnerId").value(2))
                .andExpect(jsonPath("$.data[0].newOwnerId").value(4))
                .andExpect(jsonPath("$.data[0].changedBy").value(4))
                .andExpect(jsonPath("$.data[0].reason").value("休假代管"))
                .andExpect(jsonPath("$.data[0].effectiveAt").exists())
                .andExpect(jsonPath("$.data[0].newOwnerName").value("张敏"));

        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE customer_id=?", Integer.class, customerId);
        assertThat(historyCount).isEqualTo(1);
        Integer approveAudit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='CUSTOMER_TRANSFER_APPROVE' AND target_id=?",
                Integer.class, Long.toString(customerId));
        assertThat(approveAudit).isEqualTo(1);

        // 转交单终态不可重复审批
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + (transferVersion + 1) + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void crossTeamTransfer_adminOnly() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies lead2 = login("lead2");
        AuthCookies admin = login("admin");

        long customerId = createCustomer(manager1, "ITC04-跨组");

        // lead1 申请跨组（新管家 manager2 在 team 2）→ 403
        mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":3,\"reason\":\"跨组\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // lead2（team 2 主管）对 team 1 客户不可见 → 404
        mockMvc.perform(lead2.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":9,\"reason\":\"跨组\"}"))
                .andExpect(status().isNotFound());

        // ADMIN 可跨组办理：manager1(2,team1) → manager2(3,team2)
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":3,\"reason\":\"业务调整\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(created).at("/data/id").asLong();

        // lead1（非跨组权限）尝试批准跨组单 → 403
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EFFECTIVE"));

        // 归属与团队一起变更；manager2 现在可见
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(3))
                .andExpect(jsonPath("$.data.teamId").value(2));
    }

    @Test
    void baseVersionMismatch_blocksApproveWith409_andRejectKeepsOwner() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies lead1 = login("lead1");

        long customerId = createCustomer(manager1, "ITC04-版本");
        String created = mockMvc.perform(lead1.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":4,\"reason\":\"拟转交\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long transferId = objectMapper.readTree(created).at("/data/id").asLong();

        // 申请后客户被修改（version 0 → 1），审批应 409 暂停
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"资料已变\",\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());

        // 驳回后归属不变
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-ownership-transfers/" + transferId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"资料需复核\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewReason").value("资料需复核"));
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerStaffId").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_owner_history WHERE customer_id=?", Integer.class, customerId))
                .isZero();
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
