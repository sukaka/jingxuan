package com.whale.crm.trading;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EntitlementFilteringApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void availableBalanceAndValidToAreFilteredAndPaginatedInBackend() throws Exception {
        AuthCookies manager = login("manager1");
        AuthCookies otherManager = login("manager2");
        long customerId = createCustomer(manager);
        long firstItem = insertOrderItem(customerId);
        long secondItem = insertOrderItem(customerId);
        insertEntitlement(customerId, firstItem, 101, 4, "2026-12-31", "AVAILABLE");
        insertEntitlement(customerId, firstItem, 102, 0, "2026-11-30", "AVAILABLE");
        insertEntitlement(customerId, firstItem, 103, 5, "2026-10-31", "EXPIRED");
        insertEntitlement(customerId, secondItem, 101, 2, "2027-01-31", "AVAILABLE");

        mockMvc.perform(get("/api/v1/entitlements")
                        .param("status", "AVAILABLE").param("availableOnly", "true")
                        .param("customerId", String.valueOf(customerId))
                        .param("page", "1").param("size", "1").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].availableCount").value(2));
        mockMvc.perform(get("/api/v1/entitlements")
                        .param("status", "AVAILABLE").param("availableOnly", "true")
                        .param("customerId", String.valueOf(customerId))
                        .param("validTo", "2026-12-31").param("page", "1").param("size", "20")
                        .cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].availableCount").value(4))
                .andExpect(jsonPath("$.data.items[0].validTo").value("2026-12-31"));
        mockMvc.perform(get("/api/v1/entitlements")
                        .param("status", "AVAILABLE").param("availableOnly", "true")
                        .param("customerId", String.valueOf(customerId))
                        .cookie(otherManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    private long createCustomer(AuthCookies actor) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"ITA-权益筛选\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long insertOrderItem(long customerId) {
        String orderNo = "ITA-ENT-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,
                    source_system,order_type_code,status,receiver_type)
                VALUES (1,?,?,2,2,'CRM','REGULAR','PAID','PLATFORM')
                """, orderNo, customerId);
        Long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, orderNo);
        jdbcTemplate.update("""
                INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,
                    purchase_quantity)
                VALUES (1,?,301,301,'合成权益筛选',1)
                """, orderId);
        return jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM crm_order_item WHERE tenant_id=1 AND order_id=?", Long.class, orderId);
    }

    private void insertEntitlement(long customerId, long orderItemId, int projectId, int balance,
                                   String validTo, String status) {
        jdbcTemplate.update("""
                INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,granted_count,
                    available_count,version_no,valid_from,valid_to,status)
                VALUES (1, ?, ?, ?, ?, ?, 1, '2026-01-01', ?, ?)
                """, customerId, orderItemId, projectId, Math.max(balance, 1), balance, validTo, status);
    }
}
