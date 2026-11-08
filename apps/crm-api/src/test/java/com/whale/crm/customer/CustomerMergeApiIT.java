package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C02 查重合并：同手机号两客户 → 扫描生成 SUGGESTED 候选（幂等）；未确认不迁移任何关联；
 * 确认后同事务迁移 identity/tag/followup/task、从客户 deleted=1；状态机与跨范围防护。
 */
class CustomerMergeApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void c02_sameMobileProducesCandidate_confirmMigratesAndArchives() throws Exception {
        AuthCookies admin = login("admin");

        long primary = createCustomer(admin, "ITC02-主客户", "13900001111");
        long secondary = createCustomer(admin, "ITC02-从客户", "13900001111");

        // 从客户挂身份/标签/跟进/任务
        jdbcTemplate.update(
                "INSERT INTO crm_customer_identity (tenant_id, customer_id, identity_type, source_app, external_id, status)"
                        + " VALUES (1, ?, 'WECOM', 'corp-it', 'wx-c02-001', 'LINKED')", secondary);
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/" + primary + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tagCodes\":[\"VIP\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/" + secondary + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tagCodes\":[\"VIP\",\"RISK\"]}"))
                .andExpect(status().isOk());
        long followupId = createFollowup(admin, secondary);
        long taskId = createTask(admin, secondary);

        // 无 customer:merge 权限的管家不能扫描/查看候选
        AuthCookies manager1 = login("manager1");
        mockMvc.perform(manager1.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customer-merge-candidates").cookie(manager1.cookies()))
                .andExpect(status().isForbidden());

        // 扫描生成候选；证据只含脱敏信息
        String scan = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var scanNode = objectMapper.readTree(scan).at("/data");
        assertThat(scanNode.get("created").asInt()).isGreaterThanOrEqualTo(1);
        long caseId = -1;
        for (var item : scanNode.get("items")) {
            if (item.get("primaryCustomerId").asLong() == primary
                    && item.get("secondaryCustomerId").asLong() == secondary) {
                caseId = item.get("id").asLong();
                assertThat(item.get("status").asText()).isEqualTo("SUGGESTED");
                assertThat(item.get("matchEvidence").toString()).contains("MOBILE_HASH").contains("139****1111");
                assertThat(item.get("matchEvidence").toString()).doesNotContain("13900001111");
            }
        }
        assertThat(caseId).as("扫描应生成 (primary,secondary) 候选").isPositive();

        // 重复扫描幂等：不再生成同主从候选
        String rescan = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merge-candidates/scan")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var item : objectMapper.readTree(rescan).at("/data/items")) {
            assertThat(item.get("secondaryCustomerId").asLong()).isNotEqualTo(secondary);
        }

        // 未确认：任何关联不迁移、从客户不归档
        assertThat(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_customer_identity WHERE external_id='wx-c02-001'",
                Long.class)).isEqualTo(secondary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_followup WHERE id=?",
                Long.class, followupId)).isEqualTo(secondary);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, secondary)).isZero();

        // 分页列表能查到该候选
        mockMvc.perform(get("/api/v1/customer-merge-candidates?status=SUGGESTED").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // SUGGESTED 不能直接确认；先进入人工审核，再确认合并
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"跳过审核\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEWING"));
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"同手机号人工确认\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 迁移断言：identity/tag/followup/task 归主客户；VIP 标签去重不冲突；从客户 deleted=1
        assertThat(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_customer_identity WHERE external_id='wx-c02-001'",
                Long.class)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_followup WHERE id=?",
                Long.class, followupId)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject("SELECT customer_id FROM crm_task WHERE id=?",
                Long.class, taskId)).isEqualTo(primary);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code='VIP'",
                Integer.class, primary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=? AND tag_code='RISK'",
                Integer.class, primary)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE customer_id=?", Integer.class, secondary)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, secondary)).isEqualTo(1);

        // 从客户对外 404（软归档后不可见）
        mockMvc.perform(get("/api/v1/customers/" + secondary).cookie(admin.cookies()))
                .andExpect(status().isNotFound());
        // 主客户详情带迁移后的身份（掩码）与标签
        mockMvc.perform(get("/api/v1/customers/" + primary).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.identities[0].identityType").value("WECOM"))
                .andExpect(jsonPath("$.data.identities[0].externalIdMasked").value("w***1"));

        // 终态不可再流转（当前 version=2）
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"reason\":\"已确认\"}"))
                .andExpect(status().isConflict());

        // 审计留痕
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='MERGE_CASE_CONFIRM' AND target_id=?",
                Integer.class, Long.toString(caseId));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void reviewThenReject_stateMachine() throws Exception {
        AuthCookies admin = login("admin");
        long a = createCustomer(admin, "ITC02-拒A", "13900002221");
        long b = createCustomer(admin, "ITC02-拒B", "13900002222");

        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b
                                + ",\"reason\":\"人工登记\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUGGESTED"))
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();

        // 重复登记同一对客户 → 409
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b + "}"))
                .andExpect(status().isConflict());

        // review → REVIEWING；旧 version → 409
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEWING"));
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());

        // reject → REJECTED，双方客户不受影响
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/reject"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"并非同一人\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM crm_customer WHERE id=?",
                Integer.class, b)).isZero();
        mockMvc.perform(get("/api/v1/customers/" + b).cookie(admin.cookies()))
                .andExpect(status().isOk());
    }

    @Test
    void confirm_outOfScopeCaseIs404() throws Exception {
        AuthCookies admin = login("admin");
        // 二组客户 → lead1（TEAM 1）不可见
        long a = createCustomer(admin, "ITC02-范A", "13900003331", 3);
        long b = createCustomer(admin, "ITC02-范B", "13900003332", 3);
        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + a + ",\"secondaryCustomerId\":" + b + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();

        AuthCookies lead1 = login("lead1");
        mockMvc.perform(lead1.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isNotFound());
        // lead1 列表也看不到该候选
        String list = mockMvc.perform(get("/api/v1/customer-merge-candidates?size=100")
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var item : objectMapper.readTree(list).at("/data/items")) {
            assertThat(item.get("id").asLong()).isNotEqualTo(caseId);
        }
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile) throws Exception {
        return createCustomer(actor, nickname, mobile, 2);
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile, long ownerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"mobile\":\"" + mobile
                                + "\",\"ownerStaffId\":" + ownerId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createFollowup(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/followups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"ITC02 跟进\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long createTask(AuthCookies actor, long customerId) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/tasks"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + customerId + ",\"taskType\":\"FOLLOWUP\","
                                + "\"title\":\"ITC02 任务\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }
}
