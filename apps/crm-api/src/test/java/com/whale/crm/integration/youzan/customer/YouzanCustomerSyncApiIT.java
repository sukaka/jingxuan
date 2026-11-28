package com.whale.crm.integration.youzan.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.whale.crm.integration.youzan.customer.adapter.MockYouzanCustomerPullClient;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 有赞客户拉取与 CRM 正式建档集成测试（V64，真实 MySQL 8.4 Testcontainers）：
 * 覆盖 5 个接口 RBAC 与机构拒绝、10→10→15 连续断点拉取 35 条 Mock 客户零重复、
 * ORDER_BUYERS 复购去重、手机号命中老客户产生 MOBILE_CONFLICT 且不覆盖老客户、
 * 幂等重放、并发互斥锁、重置游标重拉全跳过、以及 V64 迁移与受控回滚验证。
 */
@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.scheduling.enabled=false"})
class YouzanCustomerSyncApiIT extends AbstractMysqlIT {

    private static final String BASE = "/api/v1/integrations/youzan/customer-sync";
    private static final String SHOP_ID = MockYouzanCustomerPullClient.MOCK_SHOP_ID;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockYouzanCustomerPullClient mockPullClient;

    @BeforeEach
    void setUp() {
        cleanTestState();
    }

    @AfterEach
    void tearDown() {
        cleanTestState();
    }

    @Test
    void rbacRejectsUnauthorizedRolesInstitutionUsersAndInvalidOwner() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        AuthCookies lead = login("lead1");
        AuthCookies finance = login("finance1");
        AuthCookies inst = login("inst1");
        AuthCookies operator = login("operator1");

        String pullBody = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 10, "ownerStaffId", 2));
        String resetBody = objectMapper.writeValueAsString(Map.of("shopId", SHOP_ID, "sourceMode", "SCRM_LIST"));

        // 无 integration:youzan:manage 的内部角色及机构账号全部 403
        for (AuthCookies denied : List.of(manager, lead, finance, inst, operator)) {
            mockMvc.perform(get(BASE + "/status").param("shopId", SHOP_ID).cookie(denied.cookies()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(denied.withAuth(post(BASE + "/pull"))
                            .header("Idempotency-Key", "it-denied-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON).content(pullBody))
                    .andExpect(status().isForbidden());
            mockMvc.perform(denied.withAuth(post(BASE + "/cursor/reset"))
                            .contentType(MediaType.APPLICATION_JSON).content(resetBody))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(BASE + "/runs").param("shopId", SHOP_ID).cookie(denied.cookies()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(BASE + "/runs/1/items").cookie(denied.cookies()))
                    .andExpect(status().isForbidden());
        }

        // 缺少 Idempotency-Key → 400
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .contentType(MediaType.APPLICATION_JSON).content(pullBody))
                .andExpect(status().isBadRequest());

        // 非管家账号（finance1, id=5）作为 ownerStaffId → 400 VALIDATION_FAILED
        String badOwnerBody = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 10, "ownerStaffId", 5));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-bad-owner-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(badOwnerBody))
                .andExpect(status().isBadRequest());

        // 管理员可读 status
        mockMvc.perform(get(BASE + "/status").param("shopId", SHOP_ID).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopId").value(SHOP_ID))
                .andExpect(jsonPath("$.data.adapterName").value(MockYouzanCustomerPullClient.ADAPTER_NAME))
                .andExpect(jsonPath("$.data.officialEnabled").value(false))
                .andExpect(jsonPath("$.data.scrmCursor.hasCursor").value(false))
                .andExpect(jsonPath("$.data.orderBuyersCursor.hasCursor").value(false));
    }

    @Test
    void sequentialPull10Then10Then15ImportsAll35MockCustomersWithZeroDuplicatesIdempotencyAndReset() throws Exception {
        AuthCookies admin = login("admin");

        // 第 1 批：10 条
        String key1 = "it-scrm-10-a-" + UUID.randomUUID();
        String body10 = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 10, "ownerStaffId", 2));
        String resp1 = mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON).content(body10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.ownerName").value("王丽"))
                .andExpect(jsonPath("$.data.fetchedCount").value(10))
                .andExpect(jsonPath("$.data.createdCount").value(10))
                .andExpect(jsonPath("$.data.skippedExistingCount").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(10))
                .andReturn().getResponse().getContentAsString();
        assertThat(resp1).doesNotContain("13999000001");
        long run1Id = objectMapper.readTree(resp1).at("/data/id").asLong();

        // 幂等重放返回同一 runId 且不重复建档；同键改参数返回 409
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON).content(body10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(run1Id))
                .andExpect(jsonPath("$.data.createdCount").value(10));
        String body20 = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 20, "ownerStaffId", 2));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", key1)
                        .contentType(MediaType.APPLICATION_JSON).content(body20))
                .andExpect(status().isConflict());

        // 核对第 1 批后游标状态：第 1 页偏移 10，累计 10
        mockMvc.perform(get(BASE + "/status").param("shopId", SHOP_ID).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scrmCursor.hasCursor").value(true))
                .andExpect(jsonPath("$.data.scrmCursor.hasMore").value(true))
                .andExpect(jsonPath("$.data.scrmCursor.pageNo").value(1))
                .andExpect(jsonPath("$.data.scrmCursor.itemOffset").value(10))
                .andExpect(jsonPath("$.data.scrmCursor.totalImportedCount").value(10));

        // 第 2 批：再拉 10 条（第 11~20 条）
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-scrm-10-b-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body10))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(10))
                .andExpect(jsonPath("$.data.skippedExistingCount").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(true));

        mockMvc.perform(get(BASE + "/status").param("shopId", SHOP_ID).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scrmCursor.pageNo").value(2))
                .andExpect(jsonPath("$.data.scrmCursor.itemOffset").value(0))
                .andExpect(jsonPath("$.data.scrmCursor.totalImportedCount").value(20));

        // 第 3 批：拉剩余 15 条（第 21~35 条）→ hasMore=false
        String body15 = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 15, "ownerStaffId", 2));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-scrm-15-c-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body15))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(15))
                .andExpect(jsonPath("$.data.skippedExistingCount").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        mockMvc.perform(get(BASE + "/status").param("shopId", SHOP_ID).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scrmCursor.hasCursor").value(true))
                .andExpect(jsonPath("$.data.scrmCursor.hasMore").value(false))
                .andExpect(jsonPath("$.data.scrmCursor.totalImportedCount").value(35))
                .andExpect(jsonPath("$.data.scrmCursor.lastAnchorAt").isNotEmpty());

        // 验证真实 crm_customer / crm_customer_identity / crm_youzan_customer_link 恰好 35 条且零重复
        Integer linkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND external_customer_id LIKE 'YZ-MOCK-CUST-%'", Integer.class, SHOP_ID);
        Integer identityCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_identity WHERE tenant_id=1 AND identity_type='YOUZAN' "
                        + "AND source_app=? AND external_id LIKE 'YZ-MOCK-CUST-%'", Integer.class, SHOP_ID);
        Integer crmCustomerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT customer_id) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND external_customer_id LIKE 'YZ-MOCK-CUST-%'", Integer.class, SHOP_ID);
        assertThat(linkCount).isEqualTo(35);
        assertThat(identityCount).isEqualTo(35);
        assertThat(crmCustomerCount).isEqualTo(35);

        // 验证批次列表与明细分页接口
        mockMvc.perform(get(BASE + "/runs").param("shopId", SHOP_ID).param("sourceMode", "SCRM_LIST")
                        .param("page", "1").param("size", "20").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(3));
        mockMvc.perform(get(BASE + "/runs/" + run1Id + "/items").param("outcome", "CREATED")
                        .param("page", "1").param("size", "5").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(10))
                .andExpect(jsonPath("$.data.items.length()").value(5));

        // 重置游标后全量重拉：全部 35 条命中已绑定并跳过，零新增
        mockMvc.perform(admin.withAuth(post(BASE + "/cursor/reset"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", SHOP_ID, "sourceMode", "SCRM_LIST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scrmCursor.hasCursor").value(false))
                .andExpect(jsonPath("$.data.scrmCursor.totalImportedCount").value(0));

        String bodyAll = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "ALL", "ownerStaffId", 2));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-scrm-repull-all-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(bodyAll))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fetchedCount").value(35))
                .andExpect(jsonPath("$.data.createdCount").value(0))
                .andExpect(jsonPath("$.data.skippedExistingCount").value(35))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        // 审计日志与防明文手机号核验
        Integer syncAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='YOUZAN_CUSTOMER_SYNC'",
                Integer.class);
        Integer resetAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='YOUZAN_CUSTOMER_CURSOR_RESET'",
                Integer.class);
        Integer leakedPlainMobileInItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_youzan_customer_sync_item WHERE tenant_id=1 "
                        + "AND (mobile_masked LIKE '%1399900%' OR detail_masked LIKE '%1399900%')", Integer.class);
        assertThat(syncAudits).isGreaterThanOrEqualTo(4);
        assertThat(resetAudits).isGreaterThanOrEqualTo(1);
        assertThat(leakedPlainMobileInItems).isZero();
    }

    @Test
    void pullPopulatesCityRemarkTagsAndNonDestructivelyBackfillsExistingBoundCustomersWithoutOverwriting()
            throws Exception {
        AuthCookies admin = login("admin");
        long ordersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1", Long.class);
        long entitlementsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement WHERE tenant_id=1", Long.class);

        // 1. 拉取前 2 位 Mock SCRM 客户（含城市 杭州/上海、备注、生日、以及「贵宾客户」「有赞老客」标签）
        String pull2 = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH",
                "limit", 2, "ownerStaffId", 2, "enrichProfile", true));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-detail-pull-1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(pull2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(2));

        Long cust1Id = jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND external_customer_id='YZ-MOCK-CUST-001'", Long.class, SHOP_ID);
        Long cust2Id = jdbcTemplate.queryForObject(
                "SELECT customer_id FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND external_customer_id='YZ-MOCK-CUST-002'", Long.class, SHOP_ID);
        assertThat(cust1Id).isNotNull();
        assertThat(cust2Id).isNotNull();

        // 校验建档已直接落库省/市/区与兼容城市（HZ/SH）、备注及有赞标签（自动复用已有 VIP + 自动建「有赞老客」）
        Map<String, Object> c1Row = jdbcTemplate.queryForMap(
                "SELECT city_code, province_code, province_name, region_city_code, region_city_name, "
                        + "district_code, district_name, remark FROM crm_customer WHERE tenant_id=1 AND id=?", cust1Id);
        Map<String, Object> c2Row = jdbcTemplate.queryForMap(
                "SELECT city_code, province_code, region_city_code, district_code, remark "
                        + "FROM crm_customer WHERE tenant_id=1 AND id=?", cust2Id);
        assertThat(c1Row.get("city_code")).isEqualTo("HZ");
        assertThat(c1Row.get("province_code")).isEqualTo("330000");
        assertThat(c1Row.get("province_name")).isEqualTo("浙江省");
        assertThat(c1Row.get("region_city_code")).isEqualTo("330100");
        assertThat(c1Row.get("region_city_name")).isEqualTo("杭州市");
        assertThat(c1Row.get("district_code")).isEqualTo("330106");
        assertThat(c1Row.get("district_name")).isEqualTo("西湖区");
        assertThat(c1Row.get("remark")).isEqualTo("有赞演示同步样本 #001");
        assertThat(c2Row.get("city_code")).isEqualTo("SH");
        assertThat(c2Row.get("province_code")).isEqualTo("310000");
        assertThat(c2Row.get("district_code")).isEqualTo("310115");

        List<String> c2TagLabels = jdbcTemplate.queryForList(
                "SELECT di.option_label FROM crm_customer_tag ct "
                        + "JOIN sys_dictionary_type dt ON dt.tenant_id=ct.tenant_id AND dt.dict_code='customerTags' "
                        + "JOIN sys_dictionary_item di ON di.tenant_id=ct.tenant_id AND di.dict_type_id=dt.id "
                        + "AND di.option_code=ct.tag_code "
                        + "WHERE ct.tenant_id=1 AND ct.customer_id=?",
                String.class, cust2Id);
        assertThat(c2TagLabels).contains("贵宾客户", "有赞老客");

        // 2. 模拟老客户场景：cust1 清空城市/备注/标签（模拟历史只拉过姓名手机），cust2 手工改成北京+手工备注
        jdbcTemplate.update("UPDATE crm_customer SET city_code=NULL, remark=NULL WHERE tenant_id=1 AND id=?", cust1Id);
        jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=?", cust1Id);
        jdbcTemplate.update("UPDATE crm_customer SET city_code='BJ', remark='管家手工维护备注' WHERE tenant_id=1 AND id=?",
                cust2Id);

        // 重置游标后以 enrichProfile=true 重拉：cust1 应补回 HZ/备注/有赞老客，cust2 手工维护的 BJ 与备注绝不被覆盖
        mockMvc.perform(admin.withAuth(post(BASE + "/cursor/reset"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", SHOP_ID, "sourceMode", "SCRM_LIST"))))
                .andExpect(status().isOk());

        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-detail-backfill-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH",
                                "limit", 2, "ownerStaffId", 2, "enrichProfile", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skippedExistingCount").value(2));

        Map<String, Object> c1After = jdbcTemplate.queryForMap(
                "SELECT city_code, remark FROM crm_customer WHERE tenant_id=1 AND id=?", cust1Id);
        Map<String, Object> c2After = jdbcTemplate.queryForMap(
                "SELECT city_code, remark FROM crm_customer WHERE tenant_id=1 AND id=?", cust2Id);
        assertThat(c1After.get("city_code")).isEqualTo("HZ");
        assertThat(c1After.get("remark")).isEqualTo("有赞演示同步样本 #001");
        Integer c1TagCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=?", Integer.class, cust1Id);
        assertThat(c1TagCountAfter).isGreaterThanOrEqualTo(1);

        // cust2 手工值完全保护，绝不覆盖
        assertThat(c2After.get("city_code")).isEqualTo("BJ");
        assertThat(c2After.get("remark")).isEqualTo("管家手工维护备注");

        // 绝不触碰 CRM 正式订单与权益
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1", Long.class))
                .isEqualTo(ordersBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement WHERE tenant_id=1", Long.class))
                .isEqualTo(entitlementsBefore);
    }

    @Test
    void orderBuyersDeduplicatesRepeatOrdersAndProtectsLegacyCustomerOnMobileConflict() throws Exception {
        AuthCookies admin = login("admin");

        // 先在 CRM 建立一位已有老客户，手机号与 Mock 订单买家 #001（13998000001）相同
        String legacyResp = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "it-legacy-cust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "老客户原档保护测试",
                                "nickname", "老客原昵称",
                                "mobile", "13998000001",
                                "ownerStaffId", 2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode legacyData = objectMapper.readTree(legacyResp).at("/data");
        long legacyId = legacyData.at("/id").asLong();
        String legacyNo = legacyData.at("/customerNo").asText();

        // 按 ORDER_BUYERS 拉取全部（18 条订单记录 = 15 位去重买家，其中 1 位命中老客户手机号冲突）
        String orderAllBody = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "ORDER_BUYERS", "pullMode", "ALL",
                "ownerStaffId", 2, "enrichProfile", true));
        String runResp = mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-order-buyers-all-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(orderAllBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.fetchedCount").value(15))
                .andExpect(jsonPath("$.data.createdCount").value(14))
                .andExpect(jsonPath("$.data.mobileConflictCount").value(1))
                .andExpect(jsonPath("$.data.skippedExistingCount").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(runResp).doesNotContain("13998000001");
        long runId = objectMapper.readTree(runResp).at("/data/id").asLong();

        // 明细中可筛选出 MOBILE_CONFLICT 并携带命中的老客户 ID/编号
        String conflictItemsResp = mockMvc.perform(get(BASE + "/runs/" + runId + "/items")
                        .param("outcome", "MOBILE_CONFLICT")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].externalCustomerId").value("YZ-MOCK-ORDER-BUYER-001"))
                .andExpect(jsonPath("$.data.items[0].mobileMasked").value("139****0001"))
                .andExpect(jsonPath("$.data.items[0].conflictCustomerIds[0]").value(legacyId))
                .andExpect(jsonPath("$.data.items[0].conflictCustomerNos[0]").value(legacyNo))
                .andReturn().getResponse().getContentAsString();
        assertThat(conflictItemsResp).doesNotContain("13998000001");

        // 老客户原档案未被覆盖、也未被自动建立有赞链接
        String legacyNameAfter = jdbcTemplate.queryForObject(
                "SELECT display_name FROM crm_customer WHERE tenant_id=1 AND id=?", String.class, legacyId);
        Integer conflictLinkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND external_customer_id='YZ-MOCK-ORDER-BUYER-001'", Integer.class, SHOP_ID);
        assertThat(legacyNameAfter).isEqualTo("老客户原档保护测试");
        assertThat(conflictLinkCount).isZero();
    }

    @Test
    void runningShopKeyPreventsConcurrentRunAndExpiresStaleLeaseAutomatically() throws Exception {
        AuthCookies admin = login("admin");

        // 模拟存在活跃 RUNNING 批次 → 并发拉取返回 409 CONFLICT
        jdbcTemplate.update(
                "INSERT INTO crm_youzan_customer_sync_run (tenant_id,shop_id,source_mode,pull_mode,requested_limit,"
                        + "owner_staff_id,adapter_name,status,lease_until,created_by,started_at) "
                        + "VALUES (1,?,'SCRM_LIST','BATCH',10,2,'MOCK_DEV_ONLY','RUNNING',"
                        + "DATE_ADD(NOW(3),INTERVAL 10 MINUTE),1,NOW(3))", SHOP_ID);
        String body = objectMapper.writeValueAsString(Map.of(
                "shopId", SHOP_ID, "sourceMode", "SCRM_LIST", "pullMode", "BATCH", "limit", 5, "ownerStaffId", 2));
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-lock-conflict-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // 租约过期后自动释放僵尸锁并允许新批次执行成功
        jdbcTemplate.update(
                "UPDATE crm_youzan_customer_sync_run SET lease_until=DATE_SUB(NOW(3),INTERVAL 5 MINUTE) "
                        + "WHERE tenant_id=1 AND shop_id=? AND status='RUNNING'", SHOP_ID);
        mockMvc.perform(admin.withAuth(post(BASE + "/pull"))
                        .header("Idempotency-Key", "it-lock-recovered-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdCount").value(5));
    }

    @Test
    void v64MigrationAndRollbackCleanlyCreatesAndDropsYouzanCustomerSyncTables() throws Exception {
        String schema = "v64_yz_cust_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_youzan_customer_sync_cursor',"
                            + "'crm_youzan_customer_sync_run','crm_youzan_customer_sync_item')",
                    String.class, schema);
            assertThat(tables).containsExactlyInAnyOrder(
                    "crm_youzan_customer_sync_cursor",
                    "crm_youzan_customer_sync_run",
                    "crm_youzan_customer_sync_item");

            List<String> indexes = jdbc.queryForList(
                    "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=? "
                            + "AND index_name IN ('uk_yz_cust_cursor','idx_yz_cust_cursor_shop',"
                            + "'uk_yz_cust_run_running','idx_yz_cust_run_recent','idx_yz_cust_run_mode',"
                            + "'idx_yz_cust_item_run','idx_yz_cust_item_outcome')",
                    String.class, schema);
            assertThat(indexes).containsExactlyInAnyOrder(
                    "uk_yz_cust_cursor", "idx_yz_cust_cursor_shop",
                    "uk_yz_cust_run_running", "idx_yz_cust_run_recent", "idx_yz_cust_run_mode",
                    "idx_yz_cust_item_run", "idx_yz_cust_item_outcome");

            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V64__youzan_customer_sync_rollback.sql"));
            }

            Long remainingV64 = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_youzan_customer_sync_cursor',"
                            + "'crm_youzan_customer_sync_run','crm_youzan_customer_sync_item')",
                    Long.class, schema);
            assertThat(remainingV64).isZero();

            Long preservedCoreTables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_customer','crm_customer_identity','crm_youzan_customer_link')",
                    Long.class, schema);
            assertThat(preservedCoreTables).isEqualTo(3L);
        } finally {
            try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private void cleanTestState() {
        mockPullClient.resetFixtures();
        jdbcTemplate.update("DELETE FROM crm_youzan_customer_sync_item WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_youzan_customer_sync_run WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_youzan_customer_sync_cursor WHERE tenant_id=1");

        List<Long> createdIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT customer_id FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND (external_customer_id LIKE 'YZ-MOCK-CUST-%' "
                        + "OR external_customer_id LIKE 'YZ-MOCK-ORDER-BUYER-%')",
                Long.class, SHOP_ID);
        List<Long> legacyTestIds = jdbcTemplate.queryForList(
                "SELECT id FROM crm_customer WHERE tenant_id=1 AND display_name='老客户原档保护测试'",
                Long.class);

        jdbcTemplate.update(
                "DELETE FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=? "
                        + "AND (external_customer_id LIKE 'YZ-MOCK-CUST-%' "
                        + "OR external_customer_id LIKE 'YZ-MOCK-ORDER-BUYER-%')", SHOP_ID);
        jdbcTemplate.update(
                "DELETE FROM crm_customer_identity WHERE tenant_id=1 AND identity_type='YOUZAN' AND source_app=? "
                        + "AND (external_id LIKE 'YZ-MOCK-CUST-%' OR external_id LIKE 'YZ-MOCK-ORDER-BUYER-%')",
                SHOP_ID);
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=?", id);
            jdbcTemplate.update("DELETE FROM crm_customer WHERE tenant_id=1 AND id=?", id);
        }
        for (Long id : legacyTestIds) {
            jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=?", id);
            jdbcTemplate.update("DELETE FROM crm_customer WHERE tenant_id=1 AND id=?", id);
        }
        jdbcTemplate.update("DELETE FROM sys_dictionary_item WHERE tenant_id=1 AND option_code LIKE 'YZ_TAG_%'");
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id=1 "
                + "AND scope_code='youzan.customer.sync.pull'");
        jdbcTemplate.update("DELETE FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code IN ('YOUZAN_CUSTOMER_SYNC','YOUZAN_CUSTOMER_CURSOR_RESET')");
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
