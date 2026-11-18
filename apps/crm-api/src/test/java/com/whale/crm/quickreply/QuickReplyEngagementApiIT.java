package com.whale.crm.quickreply;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuickReplyEngagementApiIT extends AbstractMysqlIT {
    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void favoritesRecentAndStatisticsUseCurrentEmployeeAndRealUsage() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix().toUpperCase();
        long customerId = newCustomer(suffix);
        long templateId = newTemplate(suffix);

        JsonNode favorite = data(admin.withAuth(put("/api/v1/quick-replies/" + templateId + "/favorite"))
                .header("Idempotency-Key", "favorite-" + suffix));
        assertThat(favorite.at("/favorite").asBoolean()).isTrue();
        JsonNode favoriteReplay = data(admin.withAuth(put("/api/v1/quick-replies/" + templateId + "/favorite"))
                .header("Idempotency-Key", "favorite-" + suffix));
        assertThat(favoriteReplay.at("/favorite").asBoolean()).isTrue();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_quick_reply_favorite WHERE tenant_id=1 AND template_id=?",
                templateId)).isEqualTo(1);
        JsonNode list = data(get("/api/v1/quick-replies").cookie(admin.cookies()));
        assertThat(list.at("/0/id").asLong()).isEqualTo(templateId);
        assertThat(list.at("/0/favorite").asBoolean()).isTrue();

        recordUsage(admin, templateId, customerId, suffix, "COPIED", "copy-");
        recordUsage(admin, templateId, customerId, suffix, "SDK_SUCCESS", "send-");
        JsonNode afterUse = data(get("/api/v1/quick-replies").cookie(admin.cookies()));
        JsonNode current = findById(afterUse, templateId);
        assertThat(current.at("/usageCount").asLong()).isEqualTo(2);
        assertThat(current.at("/lastUsedAt").isNull()).isFalse();
        JsonNode statistics = data(get("/api/v1/quick-replies/statistics").cookie(admin.cookies()));
        JsonNode stat = findById(statistics, templateId);
        assertThat(stat.at("/totalCount").asLong()).isEqualTo(2);
        assertThat(stat.at("/copiedCount").asLong()).isEqualTo(1);
        assertThat(stat.at("/successCount").asLong()).isEqualTo(1);
        assertThat(stat.at("/successRate").asDouble()).isEqualTo(100.0);
        assertThat(stat.at("/employeeCount").asLong()).isEqualTo(1);
        JsonNode future = data(get("/api/v1/quick-replies/statistics")
                .param("from", "2999-01-01").param("to", "2999-01-02").cookie(admin.cookies()));
        assertThat(future.isEmpty()).isTrue();

        AuthCookies lead = login("lead1");
        recordUsage(lead, templateId, customerId, suffix, "SDK_FAILED", "lead-");
        JsonNode ownStatistics = data(get("/api/v1/quick-replies/statistics").cookie(lead.cookies()));
        JsonNode own = findById(ownStatistics, templateId);
        assertThat(own.at("/totalCount").asLong()).isEqualTo(1);
        assertThat(own.at("/failedCount").asLong()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/quick-replies/statistics").param("employeeId", "1").cookie(lead.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/quick-replies/" + templateId + "/favorite")
                        .cookie(login("inst1").cookies()).header("Idempotency-Key", "denied-" + suffix))
                .andExpect(status().isForbidden());

        JsonNode removed = data(admin.withAuth(delete("/api/v1/quick-replies/" + templateId + "/favorite"))
                .header("Idempotency-Key", "unfavorite-" + suffix));
        assertThat(removed.at("/favorite").asBoolean()).isFalse();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_quick_reply_favorite WHERE tenant_id=1 AND template_id=?",
                templateId)).isZero();
        assertThat(queryLong("SELECT COUNT(*) FROM crm_quick_reply_usage WHERE template_id=?", templateId)).isEqualTo(3);
    }

    private long newTemplate(String suffix) throws Exception {
        AuthCookies admin = login("admin");
        long categoryId = queryLong("SELECT id FROM crm_quick_reply_category WHERE tenant_id=1 AND code='PROJECT'");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", "推荐测试话术");
        body.put("code", "ENGAGEMENT_" + suffix);
        body.put("messageType", "TEXT");
        body.put("content", "感谢您的咨询，我们会持续跟进。");
        body.put("scene", "FOLLOWUP");
        body.put("scopeType", "GLOBAL");
        body.put("allowEdit", true);
        body.put("requirePreview", true);
        body.put("aiEnabled", false);
        body.put("featured", true);
        body.put("sortNo", 1);
        body.put("reason", "验证收藏与统计");
        JsonNode created = data(admin.withAuth(post("/api/v1/quick-replies"))
                .header("Idempotency-Key", "engagement-template-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
        return created.at("/id").asLong();
    }

    private long newCustomer(String suffix) {
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'话术统计测试客户')", "ENGAGEMENT-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "ENGAGEMENT-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001',?,2,?,'GRANTED','ACTIVE')",
                "ENGAGEMENT-EXTERNAL-" + suffix, customerId);
        return customerId;
    }

    private void recordUsage(AuthCookies user, long templateId, long customerId, String suffix,
                             String sendStatus, String keyPrefix) throws Exception {
        data(user.withAuth(post("/api/v1/quick-replies/" + templateId + "/usage"))
                .header("Idempotency-Key", keyPrefix + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                        "customerId", customerId, "externalUserid", "ENGAGEMENT-EXTERNAL-" + suffix,
                        "scene", "FOLLOWUP", "originalContent", "感谢您的咨询。", "finalContent", "感谢您的咨询，我们会跟进。",
                        "edited", true, "sendStatus", sendStatus, "clientResult", "test-synthetic-result"))));
    }

    private JsonNode findById(JsonNode array, long id) {
        for (JsonNode item : array) {
            if (item.at("/id").asLong() == id || item.at("/templateId").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("未找到话术或统计记录: " + id + " / " + array);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
