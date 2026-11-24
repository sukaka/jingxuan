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

        officialMessageService.refreshAndProcess(1L, rawEventId);

        assertThat(DETAIL_CALLS.get()).isEqualTo(1);
        assertThat(queryString("SELECT process_status FROM crm_youzan_event WHERE tenant_id=1 AND id=?", rawEventId))
                .isEqualTo("PROCESSED");
        long orderId = queryLong("SELECT crm_order_id FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shopId, tid);
        assertThat(queryString("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?", orderId)).isEqualTo("PAID");
        assertThat(grantCountForCustomer(1010L)).isEqualTo(grantsBefore + 3);
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
