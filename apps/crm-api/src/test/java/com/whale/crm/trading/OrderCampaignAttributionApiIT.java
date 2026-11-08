package com.whale.crm.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCampaignAttributionApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void orderStoresCampaignBatchChannelAndBusinessOwnerSnapshots() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        long campaignId = createCampaign(admin, "P2-ORDER-SNAPSHOT", "成交活动");
        long batchId = createBatch(admin, campaignId, "P2-ORDER-SNAPSHOT", "成交批次");
        long channelId = createChannel(admin, "P2-ORDER-SNAPSHOT", "成交渠道", 6L);
        long customerId = createCustomer(manager, "P2-ORDER-SNAPSHOT");

        JsonNode order = data(postJson("/api/v1/orders", manager,
                orderBody(customerId, campaignId, batchId, channelId))).at("/order/order");
        long orderId = order.at("/id").asLong();
        assertThat(order.at("/campaignCodeSnapshot").asText()).isEqualTo("P2-ORDER-SNAPSHOT");
        assertThat(order.at("/campaignNameSnapshot").asText()).isEqualTo("成交活动");
        assertThat(order.at("/batchCodeSnapshot").asText()).isEqualTo("P2-ORDER-SNAPSHOT");
        assertThat(order.at("/batchNameSnapshot").asText()).isEqualTo("成交批次");
        assertThat(order.at("/channelId").asLong()).isEqualTo(channelId);
        assertThat(order.at("/businessOwnerAtSaleId").asLong()).isEqualTo(6L);
        assertThat(jdbcTemplate.queryForObject("SELECT channel_id FROM crm_order WHERE id=?", Long.class, orderId))
                .isEqualTo(channelId);
        assertThat(jdbcTemplate.queryForObject("SELECT business_owner_at_sale_id FROM crm_order WHERE id=?", Long.class, orderId))
                .isEqualTo(6L);

        Map<String, Object> campaignUpdate = campaignBody("P2-ORDER-SNAPSHOT", "活动新名称");
        campaignUpdate.put("status", "ACTIVE");
        campaignUpdate.put("version", 0);
        data(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                .contentType(MediaType.APPLICATION_JSON).content(json(campaignUpdate)));
        Map<String, Object> batchUpdate = batchBody("P2-ORDER-SNAPSHOT", "批次新名称");
        batchUpdate.put("status", "ACTIVE");
        batchUpdate.put("version", 0);
        data(admin.withAuth(patch("/api/v1/campaigns/" + campaignId + "/batches/" + batchId))
                .contentType(MediaType.APPLICATION_JSON).content(json(batchUpdate)));
        Map<String, Object> channelUpdate = channelBody("P2-ORDER-SNAPSHOT", "渠道新名称", null);
        channelUpdate.put("status", "ACTIVE");
        channelUpdate.put("version", 0);
        data(admin.withAuth(patch("/api/v1/channels/" + channelId))
                .contentType(MediaType.APPLICATION_JSON).content(json(channelUpdate)));

        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.campaignCodeSnapshot").value("P2-ORDER-SNAPSHOT"))
                .andExpect(jsonPath("$.data.order.order.campaignNameSnapshot").value("成交活动"))
                .andExpect(jsonPath("$.data.order.order.batchCodeSnapshot").value("P2-ORDER-SNAPSHOT"))
                .andExpect(jsonPath("$.data.order.order.batchNameSnapshot").value("成交批次"))
                .andExpect(jsonPath("$.data.order.order.channelId").value(channelId))
                .andExpect(jsonPath("$.data.order.order.businessOwnerAtSaleId").value(6));

        campaignUpdate.put("status", "INACTIVE");
        campaignUpdate.put("version", 1);
        data(admin.withAuth(patch("/api/v1/campaigns/" + campaignId))
                .contentType(MediaType.APPLICATION_JSON).content(json(campaignUpdate)));
        mockMvc.perform(manager.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(customerId, campaignId, batchId, channelId))))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE customer_id=?", Integer.class, customerId))
                .isEqualTo(1);
    }

    @Test
    void mismatchedBatchAndInactiveChannelAreRejectedWithoutCreatingOrders() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        long firstCampaign = createCampaign(admin, "P2-ORDER-FIRST", "第一活动");
        long secondCampaign = createCampaign(admin, "P2-ORDER-SECOND", "第二活动");
        long secondBatch = createBatch(admin, secondCampaign, "P2-ORDER-SECOND", "第二批次");
        long channelId = createChannel(admin, "P2-ORDER-INACTIVE", "停用渠道", 6L);
        long customerId = createCustomer(manager, "P2-ORDER-INVALID");

        mockMvc.perform(manager.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(customerId, firstCampaign, secondBatch, null))))
                .andExpect(status().isBadRequest());

        Map<String, Object> batchUpdate = batchBody("P2-ORDER-SECOND", "第二批次");
        batchUpdate.put("status", "ARCHIVED");
        batchUpdate.put("version", 0);
        data(admin.withAuth(patch("/api/v1/campaigns/" + secondCampaign + "/batches/" + secondBatch))
                .contentType(MediaType.APPLICATION_JSON).content(json(batchUpdate)));
        mockMvc.perform(manager.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(customerId, secondCampaign, secondBatch, null))))
                .andExpect(status().isBadRequest());

        Map<String, Object> update = channelBody("P2-ORDER-INACTIVE", "停用渠道", 6L);
        update.put("status", "INACTIVE");
        update.put("version", 0);
        data(admin.withAuth(patch("/api/v1/channels/" + channelId))
                .contentType(MediaType.APPLICATION_JSON).content(json(update)));
        mockMvc.perform(manager.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderBody(customerId, null, null, channelId))))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE customer_id=?", Integer.class, customerId))
                .isZero();
    }

    @Test
    void legacyOrderRequestWithoutCampaignStillCreatesUnattributedDraft() throws Exception {
        AuthCookies manager = login("manager1");
        long customerId = createCustomer(manager, "P2-ORDER-LEGACY");
        JsonNode order = data(postJson("/api/v1/orders", manager,
                orderBody(customerId, null, null, null))).at("/order/order");
        assertThat(order.has("id")).isTrue();
        assertThat(order.at("/campaignId").isNull()).isTrue();
        assertThat(order.at("/campaignBatchId").isNull()).isTrue();
        assertThat(order.at("/campaignNameSnapshot").isNull()).isTrue();
        assertThat(order.at("/businessOwnerAtSaleId").isNull()).isTrue();
    }

    @Test
    void d01_orderTypeSnapshotSurvivesDictionaryRenameDisableAndSqlOverwrite() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        JsonNode dictionaryItem = data(postJson("/api/v1/dictionary-items", admin, Map.of(
                "dictCode", "orderTypes", "optionCode", "D01_ORDER_TYPE",
                "optionLabel", "创建时订单类型", "sortNo", 190)));
        long dictionaryItemId = dictionaryItem.at("/id").asLong();
        long customerId = createCustomer(manager, "P2-D01-ORDER-TYPE");
        Map<String, Object> createOrder = orderBody(customerId, null, null, null);
        createOrder.put("orderTypeCode", "D01_ORDER_TYPE");

        JsonNode order = data(postJson("/api/v1/orders", manager, createOrder)).at("/order/order");
        long orderId = order.at("/id").asLong();
        assertThat(order.at("/orderTypeCode").asText()).isEqualTo("D01_ORDER_TYPE");
        assertThat(order.at("/orderTypeLabelSnapshot").asText()).isEqualTo("创建时订单类型");
        assertThat(jdbcTemplate.queryForObject("SELECT order_type_label_snapshot FROM crm_order WHERE id=?",
                String.class, orderId)).isEqualTo("创建时订单类型");

        mockMvc.perform(admin.withAuth(patch("/api/v1/dictionary-items/" + dictionaryItemId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionLabel\":\"改名后订单类型\",\"sortNo\":191,\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/dictionary-items/" + dictionaryItemId + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(manager.withAuth(post("/api/v1/orders/" + orderId + "/submit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"D01旧单继续提交\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.order.order.orderTypeLabelSnapshot").value("创建时订单类型"));
        mockMvc.perform(get("/api/v1/orders/" + orderId).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.order.orderTypeCode").value("D01_ORDER_TYPE"))
                .andExpect(jsonPath("$.data.order.order.orderTypeLabelSnapshot").value("创建时订单类型"));

        mockMvc.perform(manager.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(createOrder)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_order WHERE customer_id=?",
                Integer.class, customerId)).isEqualTo(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE crm_order SET order_type_label_snapshot='非法覆盖' WHERE id=?", orderId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThat(jdbcTemplate.queryForObject("SELECT order_type_label_snapshot FROM crm_order WHERE id=?",
                String.class, orderId)).isEqualTo("创建时订单类型");
    }

    private long createCampaign(AuthCookies admin, String code, String name) throws Exception {
        return data(postJson("/api/v1/campaigns", admin, campaignBody(code, name))).at("/id").asLong();
    }

    private long createBatch(AuthCookies admin, long campaignId, String code, String name) throws Exception {
        return data(postJson("/api/v1/campaigns/" + campaignId + "/batches", admin, batchBody(code, name)))
                .at("/id").asLong();
    }

    private long createChannel(AuthCookies admin, String code, String name, Long ownerId) throws Exception {
        return data(postJson("/api/v1/channels", admin, channelBody(code, name, ownerId))).at("/id").asLong();
    }

    private long createCustomer(AuthCookies manager, String marker) throws Exception {
        return data(postJson("/api/v1/customers", manager, Map.of("nickname", marker)))
                .at("/id").asLong();
    }

    private Map<String, Object> orderBody(long customerId, Long campaignId, Long batchId, Long channelId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("receiverType", "PLATFORM");
        body.put("orderTypeCode", "REGULAR");
        body.put("campaignId", campaignId);
        body.put("campaignBatchId", batchId);
        body.put("channelId", channelId);
        body.put("payableFen", 181100);
        body.put("validTo", "2027-12-31");
        body.put("items", List.of(Map.of("skuId", 301, "quantity", 1)));
        return body;
    }

    private Map<String, Object> campaignBody(String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("campaignCode", code);
        body.put("campaignName", name);
        body.put("campaignType", "PROMOTION");
        body.put("startsAt", "2020-01-01T00:00:00Z");
        body.put("endsAt", "2030-01-01T00:00:00Z");
        return body;
    }

    private Map<String, Object> batchBody(String code, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("batchCode", code);
        body.put("batchName", name);
        body.put("startsAt", "2020-01-01T00:00:00Z");
        body.put("endsAt", "2030-01-01T00:00:00Z");
        return body;
    }

    private Map<String, Object> channelBody(String code, String name, Long ownerId) {
        Map<String, Object> body = new HashMap<>();
        body.put("channelNo", code);
        body.put("name", name);
        body.put("platformCode", "WEIBO");
        body.put("businessOwnerUserId", ownerId);
        body.put("remarks", "集成测试合成渠道");
        return body;
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String response = mockMvc.perform(builder).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private MockHttpServletRequestBuilder postJson(String url, AuthCookies actor, Object body) throws Exception {
        return actor.withAuth(post(url).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
