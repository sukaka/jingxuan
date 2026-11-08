package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanOrderEvent;
import com.whale.crm.integration.youzan.adapter.YouzanOrderLine;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YouzanOrderQueryIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockYouzanAdapter adapter;
    @Autowired
    private AesGcmCipher cipher;

    private AuthCookies admin;
    private AuthCookies manager1;
    private AuthCookies manager2;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager1 = login("manager1");
        manager2 = login("manager2");
    }

    @Test
    void mappingFailureAndMappedOrderExposeRawAndInternalFieldsWithoutSecrets() throws Exception {
        String shop = shop("fields");
        String externalCustomer = "IT-YZ-Q-CUSTOMER-" + suffix();
        seedSkuAndCustomer(shop, externalCustomer, true);
        String failedOrder = "IT-YZ-Q-FAILED-" + suffix();
        String paidOrder = "IT-YZ-Q-PAID-" + suffix();
        YouzanOrderEvent failed = event(shop, "IT-YZ-Q-FAILED-EVENT-" + suffix(), failedOrder,
                externalCustomer, "IT-YZ-Q-FAILED-TRADE-" + suffix(), "YZ-SKU-MISSING", 181100,
                Instant.parse("2026-10-24T04:00:00Z"));
        YouzanOrderEvent paid = event(shop, "IT-YZ-Q-PAID-EVENT-" + suffix(), paidOrder,
                externalCustomer, "IT-YZ-Q-PAID-TRADE-" + suffix(), "YZ-SKU-301", 181100,
                Instant.parse("2026-10-24T05:00:00Z"));
        String mobile = "13900000000";
        String nickname = "IT-YZ-Q-FICTIONAL-NICKNAME";
        String address = "IT-YZ-Q-FICTIONAL-ADDRESS";
        ObjectNode failedBody = objectMapper.valueToTree(failed);
        failedBody.put("mobile", mobile);
        failedBody.put("nickname", nickname);
        failedBody.putObject("receiver").put("contactName", nickname).put("address", address);

        postWebhook(json(failedBody));
        long failedRawId = awaitEvent(shop, failed.eventId(), "MAPPING_FAILED");
        String encrypted = queryString("SELECT payload_cipher FROM crm_youzan_event WHERE tenant_id=1 AND id=?", failedRawId);
        postWebhook(json(paid));
        awaitEvent(shop, paid.eventId(), "PROCESSED");

        JsonNode page = orders(admin, shop, 1, 20, null);
        assertThat(page.at("/total").asLong()).isEqualTo(2);
        assertThat(page.at("/items").size()).isEqualTo(2);
        JsonNode failedView = item(page, failedOrder);
        assertThat(failedView.at("/eventId").asText()).isEqualTo(failed.eventId());
        assertThat(failedView.at("/mapId").isNull()).isTrue();
        assertThat(failedView.at("/orderId").isNull()).isTrue();
        assertThat(failedView.at("/status").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(failedView.at("/mappingStatus").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(failedView.at("/orderStatus").isNull()).isTrue();
        assertThat(failedView.at("/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T04:00:00Z");
        assertThat(failedView.at("/externalAmountFen").asLong()).isEqualTo(181100);
        assertThat(failedView.at("/paidFen").isNull()).isTrue();
        assertThat(failedView.at("/verificationMethod").isNull()).isTrue();
        assertThat(failedView.at("/sourceSystem").asText()).isEqualTo("WEBHOOK");
        assertThat(failedView.at("/externalShopId").asText()).isEqualTo(shop);
        assertThat(failedView.at("/errorReason").asText()).contains("YZ-SKU-MISSING");
        assertThat(failedView.at("/payloadMaskedJson").asText())
                .contains("\"mobile\":\"***\"", "\"nickname\":\"***\"",
                        "\"contactName\":\"***\"", "\"address\":\"***\"")
                .doesNotContain(mobile, nickname, address);
        assertThat(page.toString()).doesNotContain(encrypted, mobile, nickname, address);
        assertThat(queryInt("SELECT COUNT(*) FROM crm_youzan_order_map WHERE tenant_id=1 AND shop_id=? "
                + "AND external_order_no=?", shop, failedOrder)).isZero();

        JsonNode paidView = item(page, paidOrder);
        assertThat(paidView.at("/mapId").asLong()).isPositive();
        assertThat(paidView.at("/orderId").asLong()).isPositive();
        assertThat(paidView.at("/status").asText()).isEqualTo("PROCESSED");
        assertThat(paidView.at("/mappingStatus").asText()).isEqualTo("PROCESSED");
        assertThat(paidView.at("/orderStatus").asText()).isEqualTo("PAID");
        assertThat(paidView.at("/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T05:00:00Z");
        assertThat(paidView.at("/externalAmountFen").asLong()).isEqualTo(181100);
        assertThat(paidView.at("/paidFen").asLong()).isEqualTo(181100);
        assertThat(paidView.at("/verificationMethod").asText()).isEqualTo("PROVIDER_CALLBACK");
        assertThat(paidView.at("/sourceSystem").asText()).isEqualTo("YOUZAN");
        assertThat(paidView.at("/externalShopId").asText()).isEqualTo(shop);

        JsonNode detail = data(manager1.withAuth(get("/api/v1/integrations/youzan/orders/{id}", failedRawId)));
        assertThat(detail.at("/eventId").asText()).isEqualTo(failed.eventId());
        assertThat(detail.at("/mappingStatus").asText()).isEqualTo("MAPPING_FAILED");
        assertThat(detail.at("/orderId").isNull()).isTrue();
        assertThat(detail.at("/payloadMaskedJson").isNull()).isTrue();
        assertThat(detail.toString()).doesNotContain(encrypted, mobile, nickname, address);
        JsonNode managerPage = orders(manager1, shop, 1, 20, null);
        assertThat(managerPage.at("/total").asLong()).isEqualTo(2);
        assertThat(item(managerPage, failedOrder).at("/payloadMaskedJson").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/integrations/youzan/orders").param("shopId", shop)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", failedRawId)
                        .cookie(manager2.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unlinkedRawIsVisibleToAllButHiddenFromSelfScope() throws Exception {
        String shop = shop("unlinked");
        String externalCustomer = "IT-YZ-Q-UNLINKED-CUSTOMER-" + suffix();
        seedSkuAndCustomer(shop, externalCustomer, false);
        String externalOrder = "IT-YZ-Q-UNLINKED-ORDER-" + suffix();
        YouzanOrderEvent event = event(shop, "IT-YZ-Q-UNLINKED-EVENT-" + suffix(), externalOrder,
                externalCustomer, "IT-YZ-Q-UNLINKED-TRADE-" + suffix(), "YZ-SKU-301", 181100,
                Instant.parse("2026-10-24T06:00:00Z"));

        postWebhook(json(event));
        long rawId = awaitEvent(shop, event.eventId(), "CUSTOMER_UNRESOLVED");

        JsonNode adminPage = orders(admin, shop, 1, 20, null);
        JsonNode adminView = item(adminPage, externalOrder);
        assertThat(adminView.at("/mappingStatus").asText()).isEqualTo("CUSTOMER_UNRESOLVED");
        assertThat(adminView.at("/orderId").isNull()).isTrue();
        assertThat(data(admin.withAuth(get("/api/v1/integrations/youzan/orders/{id}", rawId)))
                .at("/eventId").asText()).isEqualTo(event.eventId());
        JsonNode managerPage = orders(manager1, shop, 1, 20, null);
        assertThat(managerPage.at("/items")).isEmpty();
        assertThat(managerPage.at("/total").asLong()).isZero();
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", rawId).cookie(manager1.cookies()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void latestRawPerExternalOrderIsPagedWithTenantIsolation() throws Exception {
        String shop = shop("paging");
        String orderA = "IT-YZ-Q-PAGE-A-" + suffix();
        String orderB = "IT-YZ-Q-PAGE-B-" + suffix();
        String customer = "IT-YZ-Q-PAGE-CUSTOMER-" + suffix();
        YouzanOrderEvent oldA = event(shop, "IT-YZ-Q-PAGE-OLD-A-" + suffix(), orderA, customer,
                null, "YZ-SKU-MISSING", 100, Instant.parse("2026-10-24T07:00:00Z"));
        YouzanOrderEvent latestA = event(shop, "IT-YZ-Q-PAGE-LATEST-A-" + suffix(), orderA, customer,
                null, "YZ-SKU-MISSING", 200, Instant.parse("2026-10-24T07:01:00Z"));
        YouzanOrderEvent latestB = event(shop, "IT-YZ-Q-PAGE-LATEST-B-" + suffix(), orderB, customer,
                null, "YZ-SKU-MISSING", 300, Instant.parse("2026-10-24T07:02:00Z"));
        insertRaw(1, shop, oldA, "MAPPING_FAILED", "old error");
        insertRaw(1, shop, latestA, "CUSTOMER_UNRESOLVED", "latest error");
        insertRaw(1, shop, latestB, "RETRY", "retry error");
        String foreignOrder = "IT-YZ-Q-FOREIGN-" + suffix();
        YouzanOrderEvent foreign = event(shop, "IT-YZ-Q-FOREIGN-EVENT-" + suffix(), foreignOrder,
                customer, null, "YZ-SKU-MISSING", 400, Instant.parse("2026-10-24T07:03:00Z"));
        long foreignRawId = insertRaw(999, shop, foreign, "RETRY", "foreign error");

        JsonNode first = orders(admin, shop, 1, 1, "999");
        assertThat(first.at("/total").asLong()).isEqualTo(2);
        assertThat(first.at("/items").size()).isEqualTo(1);
        assertThat(first.at("/items/0/externalOrderNo").asText()).isEqualTo(orderB);
        assertThat(first.at("/items/0/eventId").asText()).isEqualTo(latestB.eventId());
        JsonNode second = orders(admin, shop, 2, 1, "999");
        assertThat(second.at("/total").asLong()).isEqualTo(2);
        assertThat(second.at("/items").size()).isEqualTo(1);
        assertThat(second.at("/items/0/externalOrderNo").asText()).isEqualTo(orderA);
        assertThat(second.at("/items/0/eventId").asText()).isEqualTo(latestA.eventId());
        assertThat(second.at("/items/0/mappingStatus").asText()).isEqualTo("CUSTOMER_UNRESOLVED");
        assertThat(second.at("/items/0/errorReason").asText()).isEqualTo("latest error");
        assertThat(second.at("/items/0/externalAmountFen").asLong()).isEqualTo(200);
        assertThat(second.at("/items/0/sourceUpdatedAt").asText()).isEqualTo("2026-10-24T07:01:00Z");
        assertThat(first.toString() + second).doesNotContain(foreignOrder, oldA.eventId());
        mockMvc.perform(get("/api/v1/integrations/youzan/orders/{id}", foreignRawId)
                        .cookie(admin.cookies()).header("X-Tenant-Id", "999"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private void seedSkuAndCustomer(String shop, String externalCustomer, boolean linked) {
        jdbcTemplate.update("INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) "
                + "VALUES (1,?,'YZ-SKU-301',301,'ACTIVE')", shop);
        if (linked) {
            jdbcTemplate.update("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,"
                    + "customer_id,link_type,confidence,reason,created_by) VALUES (1,?,?,1010,'MANUAL','HIGH',?,1)",
                    shop, externalCustomer, "集成测试虚构客户映射");
        }
    }

    private YouzanOrderEvent event(String shop, String eventId, String externalOrder, String externalCustomer,
                                   String tradeNo, String sku, long amountFen, Instant updatedAt) {
        return new YouzanOrderEvent(eventId, "ORDER_PAID", shop, externalOrder, externalCustomer, tradeNo,
                "PAID", amountFen, updatedAt, updatedAt, "REGULAR", 1L, 1L,
                "PLATFORM", LocalDate.parse("2027-12-31"), List.of(new YouzanOrderLine(sku, 1)));
    }

    private long insertRaw(long tenantId, String shop, YouzanOrderEvent event, String status, String error)
            throws Exception {
        String raw = json(event);
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,"
                        + "process_attempts,last_error,received_at) VALUES (?,?,?,?,?,?,?,1,'PULL',?,1,?,NOW(3))",
                tenantId, shop, event.eventId(), event.eventType(), event.externalOrderNo(), raw,
                cipher.encrypt(raw), status, error);
        return queryLong("SELECT id FROM crm_youzan_event WHERE tenant_id=? AND shop_id=? AND event_id=?",
                tenantId, shop, event.eventId());
    }

    private void postWebhook(String raw) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        mockMvc.perform(post("/api/v1/webhooks/youzan").contentType(MediaType.APPLICATION_JSON).content(raw)
                        .header("X-Youzan-Mock-Signature", adapter.signMock(raw, timestamp))
                        .header("X-Youzan-Mock-Timestamp", timestamp))
                .andExpect(status().isAccepted());
    }

    private long awaitEvent(String shop, String eventId, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<String, Object> latest = null;
        while (System.nanoTime() < deadline) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT id,process_status,last_error "
                    + "FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?", shop, eventId);
            if (rows.size() == 1) {
                latest = rows.get(0);
                String actual = (String) latest.get("process_status");
                if (expected.equals(actual)) {
                    return ((Number) latest.get("id")).longValue();
                }
                if (!"RECEIVED".equals(actual)) {
                    assertThat(actual).as("有赞查询测试事件 %s；lastError=%s", eventId, latest.get("last_error"))
                            .isEqualTo(expected);
                }
            }
            Thread.sleep(50);
        }
        assertThat(latest).as("等待有赞查询测试事件 %s 进入 %s", eventId, expected).isNotNull();
        assertThat(latest.get("process_status")).isEqualTo(expected);
        return ((Number) latest.get("id")).longValue();
    }

    private JsonNode orders(AuthCookies actor, String shop, int page, int size, String spoofedTenant) throws Exception {
        MockHttpServletRequestBuilder request = actor.withAuth(get("/api/v1/integrations/youzan/orders")
                .param("shopId", shop).param("page", Integer.toString(page)).param("size", Integer.toString(size)));
        if (spoofedTenant != null) {
            request.header("X-Tenant-Id", spoofedTenant);
        }
        return data(request);
    }

    private JsonNode item(JsonNode page, String externalOrderNo) {
        for (JsonNode item : page.at("/items")) {
            if (externalOrderNo.equals(item.at("/externalOrderNo").asText())) {
                return item;
            }
        }
        throw new AssertionError("分页缺少外部订单 " + externalOrderNo + ": " + page);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
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

    private String shop(String prefix) {
        return "IT-YZ-Q-" + prefix + "-" + suffix();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
