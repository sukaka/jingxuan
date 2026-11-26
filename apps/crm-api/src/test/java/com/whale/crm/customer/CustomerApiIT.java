package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        mockMvc.perform(get("/api/v1/customers?keyword=C0000001001&page=1&size=1").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerNo").value("C0000001001"))
                .andExpect(jsonPath("$.data.items[0].sourceCode").value("EXTERNAL_CONTENT"))
                .andExpect(jsonPath("$.data.items[0].channelCode").value("XHS"))
                .andExpect(jsonPath("$.data.items[0].spendingLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].birthday").value("1996-04-18"))
                .andExpect(jsonPath("$.data.items[0].tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].tagCodes", hasItem("VIP")))
                .andExpect(jsonPath("$.data.items[0].tagCodes", hasItem("HIGH_INTENT")))
                .andExpect(jsonPath("$.data.items[0].nextFollowupAt").isNotEmpty());
    }

    @Test
    void listOrdersNewestCreatedCustomerFirst() throws Exception {
        AuthCookies manager1 = login("manager1");
        String prefix = "ITORD-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String firstBody = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = objectMapper.readTree(firstBody).at("/data/id").asLong();

        String secondBody = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-2\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long secondId = objectMapper.readTree(secondBody).at("/data/id").asLong();

        mockMvc.perform(get("/api/v1/customers?page=1&size=2").cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(secondId))
                .andExpect(jsonPath("$.data.items[1].id").value(firstId));
    }

    @Test
    void newlyCreatedManagerViaUserApi_seesOwnCreatedCustomerImmediately_whileRemainingIsolated() throws Exception {
        AuthCookies admin = login("admin");
        String username = "it_mgr_scope_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String userBody = mockMvc.perform(admin.withAuth(post("/api/v1/users"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"新建管家\","
                                + "\"password\":\"" + DEV_PASSWORD + "\",\"userType\":\"EMPLOYEE\","
                                + "\"teamId\":1,\"roleIds\":[4]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long newManagerId = objectMapper.readTree(userBody).at("/data/id").asLong();

        AuthCookies newManager = login(username);
        String createdBody = mockMvc.perform(newManager.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"新管家客户\",\"nickname\":\"新管家自建\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerStaffId").value(newManagerId))
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(createdBody).at("/data/id").asLong();

        // 新管家自己立即可见，且不暴露其他管家客户
        mockMvc.perform(get("/api/v1/customers?page=1&size=20").cookie(newManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(customerId));
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(newManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(customerId));

        // 管理员在列表首位可见；其他管家依然不可见（C03 隔离）
        mockMvc.perform(get("/api/v1/customers?page=1&size=20").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(customerId));
        AuthCookies manager1 = login("manager1");
        mockMvc.perform(get("/api/v1/customers/" + customerId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/1001").cookie(newManager.cookies()))
                .andExpect(status().isNotFound());
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

    @Test
    void listReturnsAllSortedLongTagsWithoutGroupConcatTruncation() throws Exception {
        AuthCookies manager1 = login("manager1");
        String prefix = "ITTAG-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String body1 = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-many\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long taggedCustomerId = objectMapper.readTree(body1).at("/data/id").asLong();

        String body2 = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-empty\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long emptyTagCustomerId = objectMapper.readTree(body2).at("/data/id").asLong();

        // 25 个长度 52 字节的标签，逗号拼接总长 1324 字节 > MySQL 默认 group_concat_max_len=1024；倒序写入验证升序输出
        java.util.List<String> expectedSorted = new java.util.ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            String code = String.format("LONG_TAG_%02d_%s", i, "A".repeat(40));
            jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,?,?,2)",
                    taggedCustomerId, code);
            expectedSorted.add(code);
        }
        java.util.Collections.sort(expectedSorted);

        String response = mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "&page=1&size=20")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn().getResponse().getContentAsString();

        var items = objectMapper.readTree(response).at("/data/items");
        java.util.List<String> actualTagged = new java.util.ArrayList<>();
        java.util.List<String> actualEmpty = null;
        for (var item : items) {
            long id = item.get("id").asLong();
            if (id == taggedCustomerId) {
                item.get("tagCodes").forEach(node -> actualTagged.add(node.asText()));
            } else if (id == emptyTagCustomerId) {
                java.util.List<String> emptyTags = new java.util.ArrayList<>();
                item.get("tagCodes").forEach(node -> emptyTags.add(node.asText()));
                actualEmpty = emptyTags;
            }
        }
        assertThat(actualTagged).hasSize(25).containsExactlyElementsOf(expectedSorted);
        assertThat(actualEmpty).isNotNull().isEmpty();
    }

    @Test
    void followupDueBeforeUsesMaintainedNextFollowupAtAndExcludesRescheduledHistory() throws Exception {
        AuthCookies manager1 = login("manager1");
        String prefix = "ITP0A4-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String c1Body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-C1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long c1 = objectMapper.readTree(c1Body).at("/data/id").asLong();

        // C1 先有一条 2026-01-15 的跟进，命中 followupDueBefore=2026-06-01
        mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "p0a4-c1-f1-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + c1 + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"旧约\",\"nextFollowupAt\":\"2026-01-15T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C1&followupDueBefore=2026-06-01T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // C1 改约到 2026-12-15 -> 不再误命中 followupDueBefore=2026-06-01
        mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "p0a4-c1-f2-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + c1 + ",\"channelCode\":\"WECOM\","
                                + "\"summary\":\"改约\",\"nextFollowupAt\":\"2026-12-15T00:00:00Z\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C1&followupDueBefore=2026-06-01T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // C2: 创建 -> PATCH 延后 -> 软删除
        String c2Body = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + prefix + "-C2\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long c2 = objectMapper.readTree(c2Body).at("/data/id").asLong();
        String f3Body = mockMvc.perform(manager1.withAuth(post("/api/v1/followups"))
                        .header("Idempotency-Key", "p0a4-c2-f3-" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":" + c2 + ",\"channelCode\":\"PHONE\","
                                + "\"summary\":\"首跟\",\"nextFollowupAt\":\"2026-01-15T00:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long f3 = objectMapper.readTree(f3Body).at("/data/id").asLong();
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C2&followupDueBefore=2026-06-01T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(manager1.withAuth(patch("/api/v1/followups/" + f3))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextFollowupAt\":\"2026-12-15T00:00:00Z\",\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C2&followupDueBefore=2026-06-01T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C2&followupDueBefore=2026-12-31T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].nextFollowupAt").value("2026-12-15T00:00:00Z"));

        mockMvc.perform(manager1.withAuth(delete("/api/v1/followups/" + f3).param("version", "1")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C2&followupDueBefore=2099-01-01T00:00:00Z")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/customers?keyword=" + prefix + "-C2")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].nextFollowupAt").doesNotExist());
    }

    @Test
    void createAndUpdateWithTags_enforcesActiveCatalogAndSupportsSupervisorCreatedTags() throws Exception {
        AuthCookies lead = login("lead1");
        AuthCookies manager1 = login("manager1");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);

        // 1. 管家主管新增新标签，管家在新建客户时即可直接选择打标
        String tagResp = mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"热玛吉意向-" + suffix + "\",\"sortNo\":30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long tagId = objectMapper.readTree(tagResp).at("/data/id").asLong();
        String supervisorTagCode = objectMapper.readTree(tagResp).at("/data/optionCode").asText();

        String createdBody = mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITTAG-建档打标-" + suffix + "\","
                                + "\"tagCodes\":[\"VIP\",\"" + supervisorTagCode + "\",\"VIP\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.tagCodes", hasItem("VIP")))
                .andExpect(jsonPath("$.data.tagCodes", hasItem(supervisorTagCode)))
                .andReturn().getResponse().getContentAsString();
        long customerId = objectMapper.readTree(createdBody).at("/data/id").asLong();

        // 2. 管家不可在新建客户或打标接口自行提交未定义标签内容
        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITTAG-非法自定义\",\"tagCodes\":[\"FREE_CUSTOM_TAG\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mockMvc.perform(manager1.withAuth(put("/api/v1/customers/" + customerId + "/tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"VIP\",\"FREE_CUSTOM_TAG\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 3. 主管停用标签后：已有客户编辑可保留旧历史标签，但不能为其他客户新选该已停用标签
        mockMvc.perform(lead.withAuth(post("/api/v1/customer-tags/" + tagId + "/disable")))
                .andExpect(status().isOk());

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"保留历史标签\",\"tagCodes\":[\"VIP\",\""
                                + supervisorTagCode + "\"],\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(2))
                .andExpect(jsonPath("$.data.tagCodes", hasItem(supervisorTagCode)));

        mockMvc.perform(manager1.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITTAG-停用拒选\",\"tagCodes\":[\"" + supervisorTagCode + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 4. PATCH 传入新 tagCodes 会同步更新标签并记审计；PATCH 不传 tagCodes 时原标签保持不变
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"HIGH_INTENT\"],\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(1))
                .andExpect(jsonPath("$.data.tagCodes[0]").value("HIGH_INTENT"));

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"仅改备注不传标签\",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes.length()").value(1))
                .andExpect(jsonPath("$.data.tagCodes[0]").value("HIGH_INTENT"));

        // 5. PATCH 新增未定义标签或已停用标签（移除后试图重新添加）均 409 拒绝
        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"HIGH_INTENT\",\"FREE_CUSTOM_TAG\"],\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(manager1.withAuth(patch("/api/v1/customers/" + customerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"HIGH_INTENT\",\"" + supervisorTagCode + "\"],\"version\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        Integer tagAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE action_code='CUSTOMER_TAGS_UPDATE' AND target_id=?",
                Integer.class, Long.toString(customerId));
        assertThat(tagAuditCount).isGreaterThanOrEqualTo(1);
    }
}
