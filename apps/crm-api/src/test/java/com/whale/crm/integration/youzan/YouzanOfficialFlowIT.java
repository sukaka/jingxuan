package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.whale.crm.integration.youzan.application.YouzanOfficialMessageService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 离线有赞官方协议全链路真库集成测试：
 * 共享真实 MySQL 8.4 Testcontainers，通过本机回环 HTTP Stub 模拟有赞云官方接口，
 * 验证 RealYouzanAdapter、官方 MD5 Webhook、ORDER_CLOSED 状态机、历史未付异常收敛与结构化订单视图。
 */
@SpringBootTest(properties = {
        "app.outbox.relay.enabled=false",
        "YOUZAN_API_MIN_INTERVAL_MS=0"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YouzanOfficialFlowIT extends AbstractMysqlIT {
    private static final String OFFICIAL_WEBHOOK = "/api/v1/webhooks/youzan/official";

    private static final HttpServer STUB_SERVER;
    private static final AtomicReference<List<Map<String, Object>>> STUB_LIST_ORDERS = new AtomicReference<>(List.of());
    private static final Map<String, Map<String, Object>> STUB_DETAIL_ORDERS = new ConcurrentHashMap<>();
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final AtomicInteger LIST_CALLS = new AtomicInteger();
    private static final AtomicInteger DETAIL_CALLS = new AtomicInteger();

    static {
        try {
            STUB_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            STUB_SERVER.createContext("/auth/token", exchange -> {
                TOKEN_CALLS.incrementAndGet();
                respondJson(exchange, Map.of(
                        "success", true,
                        "code", 200,
                        "data", Map.of(
                                "access_token", "it-stub-access-token",
                                "refresh_token", "it-stub-refresh-token",
                                "expires", 1893456000000L)));
            });
            STUB_SERVER.createContext("/api/youzan.trades.sold.get/4.0.2", exchange -> {
                LIST_CALLS.incrementAndGet();
                List<Map<String, Object>> items = STUB_LIST_ORDERS.get();
                List<Map<String, Object>> wrapped = items.stream()
                        .map(item -> Map.<String, Object>of("full_order_info", item))
                        .toList();
                respondJson(exchange, Map.of(
                        "success", true,
                        "code", 200,
                        "data", Map.of(
                                "total_results", wrapped.size(),
                                "full_order_info_list", wrapped)));
            });
            STUB_SERVER.createContext("/api/youzan.trade.get/4.0.2", exchange -> {
                DETAIL_CALLS.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String tid = extractTid(body);
                Map<String, Object> detail = tid == null ? null : STUB_DETAIL_ORDERS.get(tid);
                if (detail == null) {
                    respondJson(exchange, Map.of("success", false, "code", 140400, "message", "order not found"));
                    return;
                }
                respondJson(exchange, Map.of(
                        "success", true,
                        "code", 200,
                        "data", Map.of("full_order_info", detail)));
            });
            STUB_SERVER.start();
        } catch (IOException ex) {
            throw new IllegalStateException("无法启动有赞本地模拟服务", ex);
        }
    }

    @DynamicPropertySource
    static void registerStubProperties(DynamicPropertyRegistry registry) {
        registry.add("YOUZAN_API_BASE_URL", () -> "http://127.0.0.1:" + STUB_SERVER.getAddress().getPort());
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private YouzanOfficialMessageService officialMessageService;

    private AuthCookies admin;
    private AuthCookies manager1;
    private final List<String> createdShopIds = new ArrayList<>();
    private Map<String, Object> runtimeConfigSnapshot;

    @BeforeAll
    void setUpUsers() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
    }

    @BeforeEach
    void resetStubAndSnapshotRuntimeConfig() {
        STUB_LIST_ORDERS.set(List.of());
        STUB_DETAIL_ORDERS.clear();
        TOKEN_CALLS.set(0);
        LIST_CALLS.set(0);
        DETAIL_CALLS.set(0);
        createdShopIds.clear();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT youzan_real_enabled,wecom_archive_real_enabled,wecom_official_enabled,version "
                        + "FROM crm_integration_runtime_config WHERE tenant_id=1");
        runtimeConfigSnapshot = rows.isEmpty() ? null : rows.get(0);
    }

    @AfterEach
    void restoreSharedDatabaseState() {
        for (String shopId : createdShopIds) {
            jdbcTemplate.update("UPDATE crm_youzan_shop_config SET sync_enabled=0 WHERE tenant_id=1 AND shop_id=?",
                    shopId);
        }
        if (runtimeConfigSnapshot == null) {
            jdbcTemplate.update("DELETE FROM crm_integration_runtime_config WHERE tenant_id=1");
        } else {
            jdbcTemplate.update(
                    "UPDATE crm_integration_runtime_config SET youzan_real_enabled=?,wecom_archive_real_enabled=?,"
                            + "wecom_official_enabled=? WHERE tenant_id=1",
                    runtimeConfigSnapshot.get("youzan_real_enabled"),
                    runtimeConfigSnapshot.get("wecom_archive_real_enabled"),
                    runtimeConfigSnapshot.get("wecom_official_enabled"));
        }
    }

    @AfterAll
    void stopStubServer() {
        STUB_SERVER.stop(0);
    }

    @Test
    void officialWebhookConsoleTestAndRefundProtectionWorkAgainstRealMysql() throws Exception {
        String shopId = "90" + numericSuffix();
        String clientId = "it-client-" + suffix();
        String secret = "it-secret-" + suffix();
        enableYouzanRealRuntime();

        JsonNode savedShop = saveShop(shopId, "控制台验签测试店", clientId, secret, false, null);
        assertThat(savedShop.at("/connectionStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(savedShop.at("/syncEnabled").asBoolean()).isFalse();

        String rawMissingShop = "{\"test\":true,\"msg\":\"console ping\"}";
        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", md5(clientId + rawMissingShop + secret))
                        .header("Event-Type", "test_notify")
                        .content(rawMissingShop.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        String rawPlaceholderShop = "{\"kdt_id\":88888888,\"test\":true,\"msg\":\"console ping\"}";
        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", md5(clientId + rawPlaceholderShop + secret))
                        .header("Event-Type", "trade_TradePaid")
                        .content(rawPlaceholderShop.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", "bad-signature")
                        .header("Event-Type", "test_notify")
                        .content(rawMissingShop.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String realOrderBeforeEnable = "{\"id\":\"m-disabled-" + suffix() + "\",\"kdt_id\":" + shopId
                + ",\"msg\":{\"tid\":\"E-DISABLED-1\"}}";
        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", md5(clientId + realOrderBeforeEnable + secret))
                        .header("Event-Type", "trade_TradePaid")
                        .content(realOrderBeforeEnable.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        saveShop(shopId, "控制台验签测试店", clientId, null, true, savedShop.at("/version").asLong());

        String refundPush = "{\"id\":\"m-refund-" + suffix() + "\",\"kdt_id\":" + shopId
                + ",\"msg\":{\"tid\":\"E-REFUND-1\",\"refund_id\":\"R-1\"}}";
        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", md5(clientId + refundPush + secret))
                        .header("Event-Type", "trade_refund_RefundSellerAgree")
                        .content(refundPush.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=?", shopId)).isZero();
        assertThat(DETAIL_CALLS.get()).isZero();
    }

    @Test
    void realAdapterPullHandlesPaidClosedLifecycleHistoricalIssueConvergenceAndStructuredView() throws Exception {
        String shopId = "91" + numericSuffix();
        String clientId = "it-pull-client-" + suffix();
        String secret = "it-pull-secret-" + suffix();
        String linkedCustomer = "yz-open-real-" + suffix();
        String paidTid = "E-REAL-PAID-" + suffix();
        String paidTradeNo = "TX-REAL-PAID-" + suffix();
        String draftCloseTid = "E-REAL-DRAFT-" + suffix();
        String unmappedUnpaidCloseTid = "E-REAL-UNMAPPED-UNPAID-" + suffix();
        String unmappedPaidCloseTid = "E-REAL-UNMAPPED-PAID-" + suffix();
        String neverSeenClosedTid = "E-REAL-CLOSED-ONLY-" + suffix();

        enableYouzanRealRuntime();
        saveShop(shopId, "离线模拟真店", clientId, secret, true, null);

        JsonNode tested = data(admin.withAuth(post("/api/v1/integrations/youzan/shops/{shopId}/test", shopId))
                .header("Idempotency-Key", key("test-conn")));
        assertThat(tested.at("/connectionStatus").asText()).isEqualTo("CONNECTED");
        assertThat(TOKEN_CALLS.get()).isEqualTo(1);

        seedSkuAndCustomer(shopId, "301:9", linkedCustomer, 1010L);

        int grantsBefore = grantCountForCustomer(1010L);
        STUB_LIST_ORDERS.set(List.of(
                fullOrderInfo(paidTid, "TRADE_SUCCESS", "2026-10-24 10:00:00", "2026-10-24 09:59:00",
                        "1811.00", List.of(paidTradeNo), linkedCustomer, "301", "9", 1, "13900001111", "测试敏感昵称"),
                fullOrderInfo(draftCloseTid, "WAIT_BUYER_PAY", "2026-10-24 10:00:00", null,
                        "1811.00", List.of(), linkedCustomer, "301", "9", 1, "13900002222", "待付客户"),
                fullOrderInfo(unmappedUnpaidCloseTid, "WAIT_BUYER_PAY", "2026-10-24 10:00:00", null,
                        "1811.00", List.of(), linkedCustomer, "999", "0", 1, "13900003333", "未映射未付客户"),
                fullOrderInfo(unmappedPaidCloseTid, "TRADE_SUCCESS", "2026-10-24 10:00:00", "2026-10-24 09:58:00",
                        "1811.00", List.of("TX-UNMAPPED-PAID-" + suffix()), linkedCustomer, "999", "0", 1,
                        "13900004444", "未映射已付客户"),
                fullOrderInfo(neverSeenClosedTid, "TRADE_CLOSED_BY_USER", "2026-10-24 10:00:00", null,
                        "1811.00", List.of(), "yz-open-unlinked-" + suffix(), "888", "0", 1,
                        "13900005555", "未建单已关闭客户")));

        JsonNode firstPull = data(admin.withAuth(post("/api/v1/integrations/youzan/reconcile"))
                .header("Idempotency-Key", key("real-reconcile-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "limit", 50))));
        assertThat(firstPull.at("/received").asInt()).isEqualTo(5);
        assertThat(firstPull.at("/processed").asInt()).isEqualTo(3);
        assertThat(firstPull.at("/issues").asInt()).isEqualTo(2);
        assertThat(DETAIL_CALLS.get()).isZero();

        long paidOrderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, paidTid);
        long draftOrderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, draftCloseTid);
        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", paidOrderId))
                .isEqualTo("PAID");
        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", draftOrderId))
                .isEqualTo("DRAFT");
        assertThat(grantCountForCustomer(1010L)).isEqualTo(grantsBefore + 3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, neverSeenClosedTid)).isZero();

        long unpaidHistoricalEventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, unmappedUnpaidCloseTid);
        long unpaidHistoricalIssueId = queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND event_id=? "
                + "AND issue_type='MAPPING_FAILED'", unpaidHistoricalEventId);
        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?",
                unpaidHistoricalIssueId)).isEqualTo("OPEN");

        long paidHistoricalEventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, unmappedPaidCloseTid);
        long paidHistoricalIssueId = queryLong("SELECT id FROM crm_youzan_sync_issue WHERE tenant_id=1 AND event_id=? "
                + "AND issue_type='MAPPING_FAILED'", paidHistoricalEventId);
        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?",
                paidHistoricalIssueId)).isEqualTo("OPEN");

        JsonNode managerOrders = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shopId).param("page", "1").param("size", "20")));
        JsonNode managerPaidView = findOrder(managerOrders, paidTid);
        assertThat(managerPaidView.at("/payloadMaskedJson").isNull()).isTrue();
        assertThat(managerPaidView.at("/externalCustomerId").asText()).isEqualTo(linkedCustomer);
        assertThat(managerPaidView.at("/paidAt").asText()).isEqualTo("2026-10-24T01:59:00Z");
        assertThat(managerPaidView.at("/orderTypeCode").asText()).isEqualTo("REGULAR");
        assertThat(managerPaidView.at("/items").size()).isEqualTo(1);
        assertThat(managerPaidView.at("/items/0/external_sku_id").asText()).isEqualTo("301:9");
        assertThat(managerPaidView.at("/items/0/quantity").asInt()).isEqualTo(1);
        assertThat(managerOrders.toString()).doesNotContain("13900001111", "测试敏感昵称");

        STUB_LIST_ORDERS.set(List.of(
                fullOrderInfo(draftCloseTid, "TRADE_CLOSED_BY_USER", "2026-10-24 10:05:00", null,
                        "1811.00", List.of(), linkedCustomer, "301", "9", 1, "13900002222", "待付客户"),
                fullOrderInfo(unmappedUnpaidCloseTid, "TRADE_CLOSED_BY_USER", "2026-10-24 10:05:00", null,
                        "1811.00", List.of(), linkedCustomer, "999", "0", 1, "13900003333", "未映射未付客户"),
                fullOrderInfo(unmappedPaidCloseTid, "TRADE_CLOSED", "2026-10-24 10:05:00", null,
                        "1811.00", List.of(), linkedCustomer, "999", "0", 1, "13900004444", "未映射已付客户"),
                fullOrderInfo(paidTid, "TRADE_CLOSED", "2026-10-24 10:05:00", "2026-10-24 09:59:00",
                        "1811.00", List.of(paidTradeNo), linkedCustomer, "301", "9", 1, "13900001111", "测试敏感昵称")));

        JsonNode secondPull = data(admin.withAuth(post("/api/v1/integrations/youzan/reconcile"))
                .header("Idempotency-Key", key("real-reconcile-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "limit", 50))));
        assertThat(secondPull.at("/received").asInt()).isEqualTo(4);
        assertThat(secondPull.at("/processed").asInt()).isEqualTo(3);
        assertThat(secondPull.at("/issues").asInt()).isEqualTo(1);

        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", draftOrderId))
                .isEqualTo("CANCELED");

        Map<String, Object> resolvedUnpaidIssue = jdbcTemplate.queryForMap(
                "SELECT status,last_error,resolved_by,resolved_at FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?",
                unpaidHistoricalIssueId);
        assertThat(resolvedUnpaidIssue.get("status")).isEqualTo("RESOLVED");
        assertThat((String) resolvedUnpaidIssue.get("last_error")).contains("999:0");
        assertThat(((Number) resolvedUnpaidIssue.get("resolved_by")).longValue()).isPositive();
        assertThat(resolvedUnpaidIssue.get("resolved_at")).isNotNull();
        JsonNode closedAuditAfter = objectMapper.readTree(queryString(
                "SELECT after_json FROM crm_audit_log WHERE tenant_id=1 "
                        + "AND action_code='YOUZAN_ORDER_CLOSED_IGNORED' AND target_id=? ORDER BY id DESC LIMIT 1",
                unmappedUnpaidCloseTid));
        assertThat(closedAuditAfter.at("/closedWithoutOrder").asBoolean()).isTrue();
        assertThat(closedAuditAfter.at("/resolvedHistoricalIssueIds").size()).isEqualTo(1);
        assertThat(closedAuditAfter.at("/resolvedHistoricalIssueIds/0").asLong()).isEqualTo(unpaidHistoricalIssueId);

        assertThat(queryString("SELECT status FROM crm_youzan_sync_issue WHERE tenant_id=1 AND id=?",
                paidHistoricalIssueId)).isEqualTo("OPEN");

        Map<String, Object> paidOrderRow = jdbcTemplate.queryForMap(
                "SELECT status,paid_fen FROM crm_order WHERE tenant_id=1 AND id=?", paidOrderId);
        assertThat(paidOrderRow.get("status")).isEqualTo("PAID");
        assertThat(((Number) paidOrderRow.get("paid_fen")).longValue()).isEqualTo(181100L);
        assertThat(grantCountForCustomer(1010L)).isEqualTo(grantsBefore + 3);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_sync_issue i "
                + "JOIN crm_youzan_event e ON e.tenant_id=i.tenant_id AND e.id=i.event_id "
                + "WHERE i.tenant_id=1 AND e.shop_id=? AND e.external_order_no=? "
                + "AND i.issue_type='PAYMENT_MISMATCH' AND i.status='OPEN'", shopId, paidTid)).isEqualTo(1);
    }

    @Test
    void officialWebhookOrderPushFetchesDetailViaRealAdapterAndProcessesOrder() throws Exception {
        String shopId = "92" + numericSuffix();
        String clientId = "it-wh-client-" + suffix();
        String secret = "it-wh-secret-" + suffix();
        String linkedCustomer = "yz-open-wh-" + suffix();
        String tid = "E-WH-REAL-" + suffix();
        String tradeNo = "TX-WH-REAL-" + suffix();
        String msgId = "msg-wh-" + suffix();

        enableYouzanRealRuntime();
        saveShop(shopId, "官方回调真链测试店", clientId, secret, true, null);
        seedSkuAndCustomer(shopId, "301:9", linkedCustomer, 1010L);
        STUB_DETAIL_ORDERS.put(tid, fullOrderInfo(tid, "TRADE_PAID", "2026-10-24 11:00:00", "2026-10-24 10:59:00",
                "1811.00", List.of(tradeNo), linkedCustomer, "301", "9", 1, "13900009999", "回调客户"));

        int grantsBefore = grantCountForCustomer(1010L);
        String rawPush = "{\"id\":\"" + msgId + "\",\"kdt_id\":" + shopId
                + ",\"msg\":{\"full_order_info\":{\"order_info\":{\"tid\":\"" + tid + "\"}}}}";
        mockMvc.perform(post(OFFICIAL_WEBHOOK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Client-Id", clientId)
                        .header("Event-Sign", md5(clientId + rawPush + secret))
                        .header("Event-Type", "trade_TradePaid")
                        .content(rawPush.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));

        long rawEventId = queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                shopId, msgId);
        assertThat(queryString("SELECT process_status FROM crm_youzan_event WHERE tenant_id=1 AND id=?", rawEventId))
                .isEqualTo("RECEIVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT external_customer_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                String.class, rawEventId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT campaign_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                Long.class, rawEventId)).isNull();
        assertThat(queryLong("SELECT latest_event_id FROM crm_youzan_order_latest "
                + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shopId, tid))
                .isEqualTo(rawEventId);

        officialMessageService.refreshAndProcess(1L, rawEventId);

        assertThat(DETAIL_CALLS.get()).isEqualTo(1);
        assertThat(queryString("SELECT process_status FROM crm_youzan_event WHERE tenant_id=1 AND id=?", rawEventId))
                .isEqualTo("PROCESSED");
        assertThat(queryString("SELECT external_customer_id FROM crm_youzan_event WHERE tenant_id=1 AND id=?",
                rawEventId)).isEqualTo(linkedCustomer);
        assertThat(queryLong("SELECT latest_event_id FROM crm_youzan_order_latest "
                + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shopId, tid))
                .isEqualTo(rawEventId);
        long orderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, tid);
        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", orderId)).isEqualTo("PAID");
        assertThat(grantCountForCustomer(1010L)).isEqualTo(grantsBefore + 3);
        JsonNode managerOrders = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shopId).param("page", "1").param("size", "20")));
        assertThat(findOrder(managerOrders, tid).at("/externalCustomerId").asText()).isEqualTo(linkedCustomer);
        JsonNode managerReport = data(manager1.withAuth(get("/api/v1/integrations/youzan/reconcile-report")
                .param("shopId", shopId)));
        assertThat(managerReport.at("/totalEvents").asLong()).isEqualTo(1L);
    }

    @Test
    void pullByTidParsesTradeMemoAssignsByAliasAndDisplayNameEnforcesScopeAndPreservesManualAssign() throws Exception {
        String shopId = "93" + numericSuffix();
        String clientId = "it-memo-client-" + suffix();
        String secret = "it-memo-secret-" + suffix();
        String tidAlias = "E-MEMO-ALIAS-" + suffix();
        String tidExactName = "E-MEMO-EXACT-" + suffix();
        String aliasName = "紫紫IT-" + suffix().substring(0, 6);

        Map<String, Object> manager1Row = jdbcTemplate.queryForMap(
                "SELECT id,display_name FROM sys_user WHERE tenant_id=1 AND username='manager1'");
        Map<String, Object> manager2Row = jdbcTemplate.queryForMap(
                "SELECT id,display_name FROM sys_user WHERE tenant_id=1 AND username='manager2'");
        long manager1Id = ((Number) manager1Row.get("id")).longValue();
        String manager1Name = (String) manager1Row.get("display_name");
        long manager2Id = ((Number) manager2Row.get("id")).longValue();
        String manager2Name = (String) manager2Row.get("display_name");

        AuthCookies manager2 = login("manager2");
        AuthCookies lead1 = login("lead1");
        AuthCookies lead2 = login("lead2");
        AuthCookies operator1 = login("operator1");

        enableYouzanRealRuntime();
        saveShop(shopId, "备注分配真链测试店", clientId, secret, true, null);

        String rawBuyerMobile = "13900007633";
        Map<String, Object> aliasOrderDetail = buildRichOrderInfo(
                tidAlias, "WAIT_BUYER_CONFIRM_GOODS", "已发货", "微信支付",
                "2026-11-11 15:40:31", "2026-11-11 15:40:48", "2026-11-11 15:40:49", "2026-11-11 20:42:43",
                "4999.00", List.of("TX-MEMO-1-" + suffix()), "yz-memo-cust-1-" + suffix(),
                "4898748343", "15042235417", "2026测试万能卡",
                "[{\"k\":\"城市\",\"v\":\"广州\"}]",
                "{\"姓名\":\"林小美\",\"手机号\":\"" + rawBuyerMobile + "\"}",
                "业绩：陈顾问，渠道：koc，类型：新客，来源：koc小星，账号：" + aliasName + "，到院：杭州示范院  可以延期核销申请半个月");
        STUB_DETAIL_ORDERS.put(tidAlias, aliasOrderDetail);

        // 1. 按单号单笔拉取 youzan.trade.get/4.0.2（尚未配别名 -> UNMATCHED）
        JsonNode pulled = data(admin.withAuth(post("/api/v1/integrations/youzan/orders/pull-by-tid"))
                .header("Idempotency-Key", key("pull-tid-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "tid", tidAlias))));

        assertThat(DETAIL_CALLS.get()).isEqualTo(1);
        assertThat(pulled.at("/externalOrderNo").asText()).isEqualTo(tidAlias);
        assertThat(pulled.at("/providerOrderStatus").asText()).isEqualTo("WAIT_BUYER_CONFIRM_GOODS");
        assertThat(pulled.at("/providerStatusText").asText()).isEqualTo("已发货");
        assertThat(pulled.at("/payWayDesc").asText()).isEqualTo("微信支付");
        assertThat(pulled.at("/buyerFormNameMasked").asText()).isEqualTo("林*美");
        assertThat(pulled.at("/buyerFormMobileMasked").asText()).isEqualTo("139****7633");
        assertThat(pulled.at("/memoPerformanceOwner").asText()).isEqualTo("陈顾问");
        assertThat(pulled.at("/memoChannel").asText()).isEqualTo("koc");
        assertThat(pulled.at("/memoCustomerType").asText()).isEqualTo("新客");
        assertThat(pulled.at("/memoSource").asText()).isEqualTo("koc小星");
        assertThat(pulled.at("/memoAccountName").asText()).isEqualTo(aliasName);
        assertThat(pulled.at("/memoInstitutionName").asText()).isEqualTo("杭州示范院");
        assertThat(pulled.at("/memoExtraNote").asText()).isEqualTo("可以延期核销申请半个月");
        assertThat(pulled.at("/assignmentStatus").asText()).isEqualTo("UNMATCHED");
        assertThat(pulled.at("/assignedStewardId").isNull()).isTrue();
        assertThat(pulled.toString()).doesNotContain(rawBuyerMobile, "林小美");

        // 未分配时，普通管家不可见
        JsonNode m1Before = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shopId).param("page", "1").param("size", "20")));
        assertThat(m1Before.at("/total").asLong()).isZero();

        // 2. 管理员配置账号别名 aliasName -> manager1，自动回填待分配订单
        JsonNode aliasSaved = data(admin.withAuth(post("/api/v1/integrations/youzan/steward-aliases"))
                .header("Idempotency-Key", key("save-alias-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "aliasName", aliasName,
                        "stewardUserId", manager1Id,
                        "status", "ACTIVE",
                        "remark", "花名映射集成测试",
                        "backfillPending", true))));
        assertThat(aliasSaved.at("/backfilledOrderCount").asInt()).isEqualTo(1);
        assertThat(aliasSaved.at("/alias/stewardName").asText()).isEqualTo(manager1Name);

        // 回填后：manager1 与同团队 lead1 可见，manager2 与跨团队 lead2 仍不可见
        JsonNode m1After = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shopId)
                .param("assignmentStatus", "ASSIGNED")
                .param("keyword", "杭州示范院")
                .param("page", "1").param("size", "20")));
        assertThat(m1After.at("/total").asLong()).isEqualTo(1L);
        JsonNode m1Order = findOrder(m1After, tidAlias);
        assertThat(m1Order.at("/assignedStewardId").asLong()).isEqualTo(manager1Id);
        assertThat(m1Order.at("/assignedStewardName").asText()).isEqualTo(manager1Name);
        assertThat(m1Order.at("/assignmentSource").asText()).isEqualTo("AUTO_ALIAS");
        assertThat(m1Order.at("/payloadMaskedJson").isNull()).isTrue();
        assertThat(m1After.toString()).doesNotContain(rawBuyerMobile, "林小美");

        // 传入范围外管家 ID 筛选必须收敛为空，不可放大范围
        JsonNode m1CrossFilter = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shopId).param("assignedStewardId", Long.toString(manager2Id))));
        assertThat(m1CrossFilter.at("/total").asLong()).isZero();

        assertThat(data(lead1.withAuth(get("/api/v1/integrations/youzan/orders").param("shopId", shopId)))
                .at("/total").asLong()).isEqualTo(1L);
        assertThat(data(manager2.withAuth(get("/api/v1/integrations/youzan/orders").param("shopId", shopId)))
                .at("/total").asLong()).isZero();
        assertThat(data(lead2.withAuth(get("/api/v1/integrations/youzan/orders").param("shopId", shopId)))
                .at("/total").asLong()).isZero();

        long aliasEventId = queryLong("SELECT latest_event_id FROM crm_youzan_order_latest "
                + "WHERE tenant_id=1 AND shop_id=? AND external_order_no=?", shopId, tidAlias);
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", aliasEventId).cookie(manager2.cookies()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        // 3. 批量拉取列表缺 remark_info 时自动补调 youzan.trade.get/4.0.2，并按管家姓名精确匹配 manager2
        Map<String, Object> listWithoutRemark = new LinkedHashMap<>();
        listWithoutRemark.put("order_info", Map.of("tid", tidExactName, "status", "TRADE_SUCCESS",
                "update_time", "2026-11-12 10:00:00", "pay_time", "2026-11-12 09:59:00"));
        listWithoutRemark.put("pay_info", Map.of("payment", "1811.00", "transaction", List.of("TX-MEMO-2-" + suffix()),
                "phase_payments", List.of()));
        listWithoutRemark.put("buyer_info", Map.of("yz_open_id", "yz-memo-cust-2-" + suffix()));
        listWithoutRemark.put("orders", List.of(Map.of("item_id", "301", "sku_id", "9", "num", 1)));
        STUB_LIST_ORDERS.set(List.of(listWithoutRemark));

        Map<String, Object> exactNameDetail = buildRichOrderInfo(
                tidExactName, "TRADE_SUCCESS", "交易完成", "微信支付",
                "2026-11-12 09:58:00", "2026-11-12 09:59:00", null, "2026-11-12 10:00:00",
                "1811.00", List.of("TX-MEMO-2-" + suffix()), "yz-memo-cust-2-" + suffix(),
                "301", "9", "水光示范卡", "[]", "{}",
                "业绩：林顾问，账号：" + manager2Name + "，到院：上海示范院");
        STUB_DETAIL_ORDERS.put(tidExactName, exactNameDetail);

        data(admin.withAuth(post("/api/v1/integrations/youzan/reconcile"))
                .header("Idempotency-Key", key("reconcile-missing-memo"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "limit", 50))));

        assertThat(DETAIL_CALLS.get()).isEqualTo(2);
        JsonNode m2Orders = data(manager2.withAuth(get("/api/v1/integrations/youzan/orders").param("shopId", shopId)));
        assertThat(m2Orders.at("/total").asLong()).isEqualTo(1L);
        JsonNode m2Order = findOrder(m2Orders, tidExactName);
        assertThat(m2Order.at("/assignedStewardId").asLong()).isEqualTo(manager2Id);
        assertThat(m2Order.at("/assignmentSource").asText()).isEqualTo("AUTO_DISPLAY_NAME");

        // 4. 管理员人工改派 tidAlias 给 manager2，再次单笔拉取不得覆盖 MANUAL 分配
        long assignVersion = m1Order.at("/assignmentVersion").asLong();
        JsonNode reassigned = data(admin.withAuth(put("/api/v1/integrations/youzan/orders/{tid}/assign", tidAlias))
                .param("shopId", shopId)
                .header("Idempotency-Key", key("manual-reassign-1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "stewardUserId", manager2Id,
                        "reason", "人工改派测试",
                        "version", assignVersion))));
        assertThat(reassigned.at("/assignedStewardId").asLong()).isEqualTo(manager2Id);
        assertThat(reassigned.at("/assignmentSource").asText()).isEqualTo("MANUAL");

        JsonNode repulled = data(admin.withAuth(post("/api/v1/integrations/youzan/orders/pull-by-tid"))
                .header("Idempotency-Key", key("pull-tid-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "tid", tidAlias))));
        assertThat(repulled.at("/assignedStewardId").asLong()).isEqualTo(manager2Id);
        assertThat(repulled.at("/assignmentSource").asText()).isEqualTo("MANUAL");

        // 5. 机构账号严禁访问任何新增有赞分配/别名接口
        mockMvc.perform(get("/api/v1/integrations/youzan/steward-options").cookie(operator1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/youzan/steward-aliases").cookie(operator1.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(operator1.withAuth(post("/api/v1/integrations/youzan/orders/pull-by-tid"))
                        .header("Idempotency-Key", key("op-pull"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "tid", tidAlias))))
                .andExpect(status().isForbidden());

        // 清理测试别名
        long aliasId = aliasSaved.at("/alias/id").asLong();
        mockMvc.perform(admin.withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/integrations/youzan/steward-aliases/{id}", aliasId)))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> buildRichOrderInfo(
            String tid, String status, String statusStr, String payWayDesc,
            String created, String payTime, String consignTime, String updateTime,
            String paymentYuan, List<String> transactions, String yzOpenId,
            String itemId, String skuId, String title, String skuProps,
            String buyerMessages, String tradeMemo) {
        Map<String, Object> orderInfo = new LinkedHashMap<>();
        orderInfo.put("tid", tid);
        orderInfo.put("status", status);
        orderInfo.put("status_str", statusStr);
        orderInfo.put("pay_type_desc", payWayDesc);
        orderInfo.put("created", created);
        orderInfo.put("update_time", updateTime);
        if (payTime != null) {
            orderInfo.put("pay_time", payTime);
        }
        if (consignTime != null) {
            orderInfo.put("consign_time", consignTime);
        }
        Map<String, Object> payInfo = new LinkedHashMap<>();
        payInfo.put("payment", paymentYuan);
        payInfo.put("pay_way_desc", payWayDesc);
        payInfo.put("transaction", transactions);
        payInfo.put("phase_payments", List.of());

        Map<String, Object> buyerInfo = new LinkedHashMap<>();
        buyerInfo.put("yz_open_id", yzOpenId);
        buyerInfo.put("buyer_phone", "$CIPHER_PHONE$1$");
        buyerInfo.put("fans_nickname", "$CIPHER_NICK$1$");

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("item_id", itemId);
        line.put("sku_id", skuId);
        line.put("num", 1);
        line.put("title", title);
        line.put("price", paymentYuan);
        line.put("payment", paymentYuan);
        line.put("sku_properties_name", skuProps);
        line.put("buyer_messages", buyerMessages);

        Map<String, Object> full = new LinkedHashMap<>();
        full.put("order_info", orderInfo);
        full.put("pay_info", payInfo);
        full.put("buyer_info", buyerInfo);
        full.put("remark_info", Map.of("buyer_message", "", "star", 0, "trade_memo", tradeMemo));
        full.put("orders", List.of(line));
        return full;
    }

    private void enableYouzanRealRuntime() throws Exception {
        JsonNode current = data(admin.withAuth(get("/api/v1/integrations/runtime-config")));
        long version = current.at("/version").asLong();
        boolean wecomArchive = current.at("/wecomArchiveRealEnabled").asBoolean(false);
        boolean wecomOfficial = current.at("/wecomOfficialEnabled").asBoolean(false);
        data(admin.withAuth(put("/api/v1/integrations/runtime-config"))
                .header("Idempotency-Key", key("runtime-enable"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "youzanRealEnabled", true,
                        "wecomArchiveRealEnabled", wecomArchive,
                        "wecomOfficialEnabled", wecomOfficial,
                        "version", version))));
    }

    private JsonNode saveShop(String shopId, String shopName, String clientId, String clientSecret,
                              boolean syncEnabled, Long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shopId", shopId);
        body.put("shopName", shopName);
        body.put("clientId", clientId);
        if (clientSecret != null) {
            body.put("clientSecret", clientSecret);
        }
        body.put("syncEnabled", syncEnabled);
        if (version != null) {
            body.put("version", version);
        }
        JsonNode saved = data(admin.withAuth(post("/api/v1/integrations/youzan/shops"))
                .header("Idempotency-Key", key("shop-save"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
        if (!createdShopIds.contains(shopId)) {
            createdShopIds.add(shopId);
        }
        return saved;
    }

    private void seedSkuAndCustomer(String shopId, String externalSkuId, String externalCustomerId, long customerId) {
        jdbcTemplate.update("INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) "
                + "VALUES (1,?,?,301,'ACTIVE')", shopId, externalSkuId);
        jdbcTemplate.update("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,"
                        + "customer_id,link_type,confidence,reason,created_by) VALUES (1,?,?,?,'MANUAL','HIGH',?,1)",
                shopId, externalCustomerId, customerId, "离线协议集成测试映射");
    }

    private static Map<String, Object> fullOrderInfo(String tid, String status, String updateTime, String payTime,
                                                     String paymentYuan, List<String> transactions, String yzOpenId,
                                                     String itemId, String skuId, int quantity,
                                                     String buyerPhone, String fansNickname) {
        Map<String, Object> orderInfo = new LinkedHashMap<>();
        orderInfo.put("tid", tid);
        orderInfo.put("status", status);
        orderInfo.put("update_time", updateTime);
        if (payTime != null) {
            orderInfo.put("pay_time", payTime);
        }
        Map<String, Object> payInfo = new LinkedHashMap<>();
        payInfo.put("payment", paymentYuan);
        payInfo.put("transaction", transactions);
        payInfo.put("phase_payments", List.of());

        Map<String, Object> buyerInfo = new LinkedHashMap<>();
        buyerInfo.put("yz_open_id", yzOpenId);
        buyerInfo.put("buyer_phone", buyerPhone);
        buyerInfo.put("fans_nickname", fansNickname);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("item_id", itemId);
        line.put("sku_id", skuId);
        line.put("num", quantity);

        Map<String, Object> full = new LinkedHashMap<>();
        full.put("order_info", orderInfo);
        full.put("pay_info", payInfo);
        full.put("buyer_info", buyerInfo);
        full.put("remark_info", Map.of("buyer_message", "", "star", 0, "trade_memo", ""));
        full.put("orders", List.of(line));
        return full;
    }

    private static void respondJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String extractTid(String body) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String tid = node.path("tid").asText(null);
            return tid == null || tid.isBlank() ? null : tid;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String md5(String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode findOrder(JsonNode page, String externalOrderNo) {
        for (JsonNode item : page.at("/items")) {
            if (externalOrderNo.equals(item.at("/externalOrderNo").asText())) {
                return item;
            }
        }
        throw new AssertionError("订单列表缺少 " + externalOrderNo + ": " + page);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private int grantCountForCustomer(long customerId) {
        Integer value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(delta_count),0) FROM crm_entitlement_ledger l "
                + "JOIN crm_entitlement e ON e.id=l.entitlement_id WHERE l.tenant_id=1 AND e.customer_id=? "
                + "AND l.txn_type='GRANT'", Integer.class, customerId);
        return value == null ? 0 : value;
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private long queryLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }

    private static String key(String prefix) {
        return "it-yz-official-" + prefix + "-" + UUID.randomUUID();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String numericSuffix() {
        return Long.toString(Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L));
    }
}
