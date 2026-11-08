package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户 CRUD 端到端：C01 无手机号建档、C03 跨管家数据隔离、手机号加密/脱敏、乐观锁、
 * ownerId 收窄、标签替换、幂等键、审计落库且无明文手机号。
 */
class CustomerApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------- C01 ----------

    @Test
    void c01_createWithoutMobile_assignsServerCustomerNoAndNullMobile() throws Exception {
        AuthCookies manager1 = login("manager1");
        String body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-无名客户\",\"cityCode\":\"BJ\",\"sourceCode\":\"DIRECT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.customerNo").exists())
                .andExpect(jsonPath("$.data.mobileMasked").doesNotExist())
                .andExpect(jsonPath("$.data.ownerStaffId").value(2))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).at("/data/id").asLong();
        String customerNo = objectMapper.readTree(body).at("/data/customerNo").asText();
        assertThat(customerNo).matches("C\\d{10,}");

        // 数据库断言：手机号列保持 NULL，不假塞（C01）
        var row = jdbcTemplate.queryForMap(
                "SELECT mobile_cipher, mobile_hash, customer_no FROM crm_customer WHERE id = ?", id);
        assertThat(row.get("mobile_cipher")).isNull();
        assertThat(row.get("mobile_hash")).isNull();
        assertThat(row.get("customer_no")).isEqualTo(customerNo);
    }

    @Test
    void create_requiresNicknameOrDisplayName() throws Exception {
        AuthCookies manager1 = login("manager1");
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cityCode\":\"BJ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 手机号加密与脱敏 ----------

    @Test
    void createWithMobile_storesCipherAndHashOnly_maskOnRead() throws Exception {
        AuthCookies manager1 = login("manager1");
        String body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-加密\",\"mobile\":\"13911112222\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****2222"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).at("/data/id").asLong();

        var row = jdbcTemplate.queryForMap(
                "SELECT mobile_cipher, mobile_hash FROM crm_customer WHERE id = ?", id);
        String cipher = (String) row.get("mobile_cipher");
        String hash = (String) row.get("mobile_hash");
        assertThat(cipher).isNotNull().doesNotContain("13911112222");
        assertThat(hash).isNotNull().hasSize(64).doesNotContain("13911112222");

        // 审计快照不得含明文手机号
        var audit = jdbcTemplate.queryForMap(
                "SELECT after_json FROM crm_audit_log WHERE action_code='CUSTOMER_CREATE' AND target_id=?",
                Long.toString(id));
        assertThat((String) audit.get("after_json")).doesNotContain("13911112222").contains("139****2222");

        // 详情同样脱敏；重复创建不产生明文泄漏
        mockMvc.perform(get("/api/v1/customers/" + id).cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****2222"));

        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-坏手机号\",\"mobile\":\"abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listMapsSourceChannelTagsAndFollowupFieldsFromDatabase() throws Exception {
        AuthCookies manager1 = login("manager1");
        mockMvc.perform(get("/api/v1/customers?page=1&size=1").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sourceCode").value("EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.items[0].channelCode").value("XHS"))
                .andExpect(jsonPath("$.data.items[0].spendingLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].birthday").value("1996-04-18"))
                .andExpect(jsonPath("$.data.items[0].tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].tagCodes", hasItem("VIP")))
                .andExpect(jsonPath("$.data.items[0].tagCodes", hasItem("HIGH_INTENT")))
                .andExpect(jsonPath("$.data.items[0].nextFollowupAt").isNotEmpty());
    }

    // ---------- C03 数据隔离 ----------

    @Test
    void c03_otherManagerCannotSeeAndOwnerIdOnlyNarrows() throws Exception {
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");

        String body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC03-隔离\",\"mobile\":\"13933334444\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).at("/data/id").asLong();

        // manager2 详情 404（不泄露存在性）
        mockMvc.perform(get("/api/v1/customers/" + id).cookie(manager2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // manager2 列表不可见（含盲索引手机号等值查）
        mockMvc.perform(get("/api/v1/customers?keyword=ITC03-隔离").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/customers?keyword=13933334444").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/customers?keyword=13933334444").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(id));

        // ownerId 只能收窄：manager2 传 ownerId=2（manager1）得到空页而不是对方数据
        mockMvc.perform(get("/api/v1/customers?ownerId=2").cookie(manager2.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        // manager1 传本人 ownerId 正常收窄命中
        mockMvc.perform(get("/api/v1/customers?ownerId=2&keyword=ITC03-隔离").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // manager2 修改/标签/积分均 404
        mockMvc.perform(manager2.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"x\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(manager2.withAuth(put("/api/v1/customers/" + id + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"VIP\"]}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + id + "/points").cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void institutionUserHasNoCustomerAccess() throws Exception {
        AuthCookies inst1 = login("inst1");
        mockMvc.perform(get("/api/v1/customers").cookie(inst1.cookies()))
                .andExpect(status().isForbidden());
    }

    // ---------- 乐观锁与标签 ----------

    @Test
    void patchAndTags_optimisticLockAndAudit() throws Exception {
        AuthCookies manager1 = login("manager1");
        String body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-锁\",\"mobile\":\"13955556666\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).at("/data/id").asLong();

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-锁改\",\"cityCode\":\"SH\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("ITC01-锁改"))
                .andExpect(jsonPath("$.data.cityCode").value("SH"))
                .andExpect(jsonPath("$.data.mobileMasked").value("139****6666"))
                .andExpect(jsonPath("$.data.version").value(1));

        // 旧版本 → 409
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"并发\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 手机号改号：新密文与新盲索引，旧哈希不可用
        String oldHash = jdbcTemplate.queryForObject(
                "SELECT mobile_hash FROM crm_customer WHERE id = ?", String.class, id);
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\":\"13977778888\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mobileMasked").value("139****8888"));
        String newHash = jdbcTemplate.queryForObject(
                "SELECT mobile_hash FROM crm_customer WHERE id = ?", String.class, id);
        assertThat(newHash).isNotEqualTo(oldHash);

        // 标签全量替换 + 审计 before/after
        mockMvc.perform(manager1.withAuth(put("/api/v1/customers/" + id + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"VIP\",\"HIGH_INTENT\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(2));
        mockMvc.perform(manager1.withAuth(put("/api/v1/customers/" + id + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"VIP\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(1))
                .andExpect(jsonPath("$.data.tagCodes[0]").value("VIP"));
        Integer tagAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='CUSTOMER_TAGS_UPDATE' AND target_id=?",
                Integer.class, Long.toString(id));
        assertThat(tagAudits).isGreaterThanOrEqualTo(2);
    }

    // ---------- 创建幂等 ----------

    @Test
    void create_withIdempotencyKey_replaysSameResultAndRejectsDifferentBody() throws Exception {
        AuthCookies manager1 = login("manager1");
        String payload = "{\"nickname\":\"ITC01-幂等\"}";
        String first = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "it-idem-c01-001")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(first).at("/data/id").asLong();

        // 同键同请求体回放 → 返回首次结果（不新建）
        String replay = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "it-idem-c01-001")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).at("/data/id").asLong()).isEqualTo(id);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer WHERE nickname='ITC01-幂等'", Integer.class);
        assertThat(count).isEqualTo(1);

        // 同键不同请求体 → 409
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "it-idem-c01-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITC01-幂等-改\"}"))
                .andExpect(status().isConflict());
    }
}
