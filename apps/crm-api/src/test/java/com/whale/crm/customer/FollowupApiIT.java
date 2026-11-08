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
