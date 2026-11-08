package com.whale.crm.integration.youzan.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class YouzanV8SchemaIT extends AbstractMysqlIT {
    private static final String SHOP = "it-youzan-schema";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthCookies admin;
    private long customerId;
    private long orderId;
    private long eventId;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin");
        jdbcTemplate.update("DELETE FROM crm_youzan_sync_cursor WHERE shop_id=?", SHOP);
        jdbcTemplate.update("DELETE FROM crm_youzan_sync_issue WHERE event_id IN (SELECT id FROM crm_youzan_event WHERE shop_id=?)", SHOP);
        jdbcTemplate.update("DELETE FROM crm_youzan_customer_link WHERE shop_id=?", SHOP);
        jdbcTemplate.update("DELETE FROM crm_youzan_sku_map WHERE shop_id=?", SHOP);
        jdbcTemplate.update("DELETE FROM crm_youzan_order_map WHERE shop_id=?", SHOP);
        jdbcTemplate.update("DELETE FROM crm_youzan_event WHERE shop_id=?", SHOP);
        customerId = createCustomer();
        orderId = createOrder(customerId);
        eventId = insertEvent("schema-event-1", "schema-order-1");
    }

    @Test
    void rawEventAndOrderMapAreUniqueByBusinessKeys() {
        assertThatThrownBy(() -> insertEvent("schema-event-1", "schema-order-1"))
                .isInstanceOf(DuplicateKeyException.class);
        insertOrderMap("schema-order-1", orderId);
        assertThatThrownBy(() -> insertOrderMap("schema-order-1", orderId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> insertOrderMap("schema-order-2", orderId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void skuCustomerIssueAndCursorConstraintsAreEnforced() {
        jdbcTemplate.update("INSERT INTO crm_youzan_sku_map (tenant_id,shop_id,external_sku_id,crm_sku_id,status) "
                + "VALUES (1,?,?,?,'ACTIVE')", SHOP, "schema-sku", 301);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO crm_youzan_sku_map "
                + "(tenant_id,shop_id,external_sku_id,crm_sku_id,status) VALUES (1,?,?,?,'ACTIVE')", SHOP, "schema-sku", 301))
                .isInstanceOf(DuplicateKeyException.class);

        jdbcTemplate.update("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,customer_id,"
                + "link_type,confidence,created_by) VALUES (1,?,?,?,'MANUAL','HIGH',1)", SHOP, "schema-customer", customerId);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO crm_youzan_customer_link "
                + "(tenant_id,shop_id,external_customer_id,customer_id,link_type,confidence,created_by) "
                + "VALUES (1,?,?,?,'MANUAL','HIGH',1)", SHOP, "schema-customer", customerId))
                .isInstanceOf(DuplicateKeyException.class);

        jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue (tenant_id,event_id,issue_type,status) "
                + "VALUES (1,?,'MAPPING_FAILED','OPEN')", eventId);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue "
                + "(tenant_id,event_id,issue_type,status) VALUES (1,?,'MAPPING_FAILED','OPEN')", eventId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO crm_youzan_sync_issue "
                + "(tenant_id,event_id,issue_type,status) VALUES (1,?,'RETRY','OPEN')", -999L))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("INSERT INTO crm_youzan_sync_cursor (tenant_id,shop_id,cursor_value) VALUES (1,?,'cursor-1')", SHOP);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO crm_youzan_sync_cursor "
                + "(tenant_id,shop_id,cursor_value) VALUES (1,?,'cursor-2')", SHOP))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private long insertEvent(String eventKey, String externalOrderNo) {
        jdbcTemplate.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                        + "payload_masked_json,payload_cipher,signature_valid,source_system,process_status,received_at) "
                        + "VALUES (1,?,?,?,?,?,'synthetic-cipher',1,'WEBHOOK','RECEIVED',NOW(3))",
                SHOP, eventKey, "ORDER_PAID", externalOrderNo, "{\"orderNo\":\"IT-MASKED\"}");
        return jdbcTemplate.queryForObject("SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id=? AND event_id=?",
                Long.class, SHOP, eventKey);
    }

    private void insertOrderMap(String externalOrderNo, long crmOrderId) {
        jdbcTemplate.update("INSERT INTO crm_youzan_order_map (tenant_id,shop_id,external_order_no,crm_order_id,"
                + "last_external_updated_at) VALUES (1,?,?,?,NOW(3))", SHOP, externalOrderNo, crmOrderId);
    }

    private long createCustomer() throws Exception {
        JsonNode response = data(admin.withAuth(post("/api/v1/customers"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", "IT-YOUZAN-SCHEMA-" + UUID.randomUUID(), "ownerStaffId", 2))));
        return response.at("/id").asLong();
    }

    private long createOrder(long customerId) throws Exception {
        Map<String, Object> body = Map.of("customerId", customerId, "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR", "campaignId", 1, "campaignBatchId", 1,
                "payableFen", 181100, "validTo", "2027-12-31",
                "items", java.util.List.of(Map.of("skuId", 301, "quantity", 1)));
        JsonNode response = data(admin.withAuth(post("/api/v1/orders"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        return response.at("/order/order/id").asLong();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
