package com.whale.crm.annualreport;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户详情页「有赞历史订单（只读快照）」MySQL 8.4 端到端集成测试：
 * 覆盖多源身份关联（crm_youzan_customer_link / crm_customer_identity）、已付/全部过滤、
 * 金额汇总（整数分）、行级数据范围隔离（跨管家 404 / 机构 403）、无明文 yz_open_id 泄露、
 * 以及严禁改写 crm_order / crm_entitlement。
 */
@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.scheduling.enabled=false"})
class CustomerYouzanHistoryOrderApiIT extends AbstractMysqlIT {

    private static final String TEST_SHOP_ID = "SHOP_HIST_IT_01";
    private static final String TEST_OPEN_ID = "yz_open_hist_secret_001";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long createdCustomerId;
    private Long unlinkedCustomerId;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void queriesReadOnlyYouzanHistoryOrdersWithScopeIsolationAndZeroLedgerSideEffects() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");
        AuthCookies manager2 = login("manager2");
        AuthCookies inst1 = login("inst1");

        // 1. 新建归属 manager1 (userId=2) 的已关联客户与未关联客户
        String c1Resp = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "hist-cust-1-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "有赞历史单测试客户",
                                "mobile", "13966001122",
                                "ownerStaffId", 2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        createdCustomerId = objectMapper.readTree(c1Resp).at("/data/id").asLong();

        String c2Resp = mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", "hist-cust-2-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "未绑有赞测试客户",
                                "ownerStaffId", 2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        unlinkedCustomerId = objectMapper.readTree(c2Resp).at("/data/id").asLong();

        // 仅通过 crm_youzan_customer_link 绑定（未写 crm_youzan_annual_profile.customer_id 也能自动命中）
        jdbcTemplate.update(
                "INSERT INTO crm_youzan_customer_link (tenant_id, shop_id, external_customer_id, customer_id, "
                        + "link_type, confidence, reason, created_by) VALUES (1, ?, ?, ?, 'STRONG', 'HIGH', 'IT', 1)",
                TEST_SHOP_ID, TEST_OPEN_ID, createdCustomerId);

        // 写入 3 笔只读有赞历史订单快照（2 笔已支付含 1 笔部分退款 + 1 笔已关闭未付），绝不写 crm_order / crm_entitlement
        String itemsJson1 = "[{\"itemId\":\"1001\",\"skuId\":\"2001\",\"title\":\"黄金微针全脸\",\"skuProps\":\"单次\",\"num\":1,\"priceFen\":198000}]";
        String itemsJson2 = "[{\"itemId\":\"1002\",\"skuId\":\"2002\",\"title\":\"超光子嫩肤\",\"skuProps\":\"全模式\",\"num\":2,\"priceFen\":120000}]";
        String itemsJson3 = "[{\"itemId\":\"1003\",\"skuId\":\"2003\",\"title\":\"未支付取消单\",\"skuProps\":null,\"num\":1,\"priceFen\":50000}]";

        jdbcTemplate.update(
                "INSERT INTO crm_youzan_annual_order_snapshot "
                        + "(tenant_id, shop_id, tid, yz_open_id, order_status, is_paid, ordered_at, paid_at, "
                        + "payment_fen, refunded_fen, title_summary, items_json, synced_at) VALUES "
                        + "(1, ?, 'E_HIST_001', ?, 'TRADE_SUCCESS', 1, '2025-05-01 10:00:00', '2025-05-01 10:05:00', "
                        + "198000, 0, '黄金微针全脸', ?, NOW(3)),"
                        + "(1, ?, 'E_HIST_002', ?, 'TRADE_SUCCESS', 1, '2026-08-15 12:00:00', '2026-08-15 12:02:00', "
                        + "240000, 40000, '超光子嫩肤', ?, NOW(3)),"
                        + "(1, ?, 'E_HIST_003', ?, 'TRADE_CLOSED_BY_USER', 0, '2026-09-01 08:00:00', NULL, "
                        + "50000, 0, '未支付取消单', ?, NOW(3))",
                TEST_SHOP_ID, TEST_OPEN_ID, itemsJson1,
                TEST_SHOP_ID, TEST_OPEN_ID, itemsJson2,
                TEST_SHOP_ID, TEST_OPEN_ID, itemsJson3);

        long crmOrdersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1", Long.class);
        long crmEntitlementsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_entitlement WHERE tenant_id=1", Long.class);

        // 2. 归属管家 manager1 默认查询（paidOnly=true）→ 返回 2 笔已支付，且按时间倒序 + 汇总准确 + 无 yzOpenId 泄露
        String paidBody = mockMvc.perform(get("/api/v1/customers/" + createdCustomerId + "/youzan-history-orders")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(createdCustomerId))
                .andExpect(jsonPath("$.data.hasYouzanIdentity").value(true))
                .andExpect(jsonPath("$.data.linkedShopIds[0]").value(TEST_SHOP_ID))
                .andExpect(jsonPath("$.data.summary.totalOrderCount").value(3))
                .andExpect(jsonPath("$.data.summary.paidOrderCount").value(2))
                .andExpect(jsonPath("$.data.summary.totalPaymentFen").value(438000))
                .andExpect(jsonPath("$.data.summary.totalRefundedFen").value(40000))
                .andExpect(jsonPath("$.data.summary.netSpendFen").value(398000))
                .andExpect(jsonPath("$.data.orders.total").value(2))
                .andExpect(jsonPath("$.data.orders.items.length()").value(2))
                .andExpect(jsonPath("$.data.orders.items[0].tid").value("E_HIST_002"))
                .andExpect(jsonPath("$.data.orders.items[0].netPaymentFen").value(200000))
                .andExpect(jsonPath("$.data.orders.items[0].items[0].title").value("超光子嫩肤"))
                .andExpect(jsonPath("$.data.orders.items[1].tid").value("E_HIST_001"))
                .andReturn().getResponse().getContentAsString();
        assertThat(paidBody).doesNotContain(TEST_OPEN_ID).doesNotContain("13966001122");

        // 3. paidOnly=false → 返回全部 3 笔（包含已关闭单）
        mockMvc.perform(get("/api/v1/customers/" + createdCustomerId + "/youzan-history-orders")
                        .param("paidOnly", "false")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.total").value(3))
                .andExpect(jsonPath("$.data.orders.items.length()").value(3))
                .andExpect(jsonPath("$.data.orders.items[0].tid").value("E_HIST_003"));

        // 4. 未绑定有赞的客户 → hasYouzanIdentity=false + 空列表
        mockMvc.perform(get("/api/v1/customers/" + unlinkedCustomerId + "/youzan-history-orders")
                        .cookie(manager1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasYouzanIdentity").value(false))
                .andExpect(jsonPath("$.data.orders.total").value(0));

        // 5. 跨管家 manager2 越权访问 → 404 不泄露存在性；机构账号 inst1 → 403 拒绝
        mockMvc.perform(get("/api/v1/customers/" + createdCustomerId + "/youzan-history-orders")
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + createdCustomerId + "/youzan-history-orders")
                        .cookie(inst1.cookies()))
                .andExpect(status().isForbidden());

        // 6. 绝不写入正式订单或发放权益
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE tenant_id=1", Long.class))
                .isEqualTo(crmOrdersBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_entitlement WHERE tenant_id=1", Long.class))
                .isEqualTo(crmEntitlementsBefore);
    }

    private void cleanUp() {
        jdbcTemplate.update(
                "DELETE FROM crm_youzan_annual_order_snapshot WHERE tenant_id=1 AND shop_id=?", TEST_SHOP_ID);
        jdbcTemplate.update(
                "DELETE FROM crm_youzan_customer_link WHERE tenant_id=1 AND shop_id=?", TEST_SHOP_ID);
        if (createdCustomerId != null) {
            jdbcTemplate.update("DELETE FROM crm_customer_identity WHERE tenant_id=1 AND customer_id=?", createdCustomerId);
            jdbcTemplate.update("DELETE FROM crm_customer WHERE tenant_id=1 AND id=?", createdCustomerId);
            createdCustomerId = null;
        }
        if (unlinkedCustomerId != null) {
            jdbcTemplate.update("DELETE FROM crm_customer WHERE tenant_id=1 AND id=?", unlinkedCustomerId);
            unlinkedCustomerId = null;
        }
    }
}
