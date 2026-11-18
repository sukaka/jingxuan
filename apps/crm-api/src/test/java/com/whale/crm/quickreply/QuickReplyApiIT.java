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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuickReplyApiIT extends AbstractMysqlIT {
    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void dynamicPreviewUsesRealFactsAndBlocksMissingVariables() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        long templateId = templateId("PROJECT_REMAINING");

        JsonNode templates = data(get("/api/v1/quick-replies").cookie(admin.cookies()));
        assertThat(templates.toString()).contains("PROJECT_REMAINING");
        JsonNode variables = data(get("/api/v1/quick-replies/variables").cookie(admin.cookies()));
        assertThat(variables.size()).isEqualTo(15);
        JsonNode preview = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId + "/preview"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", fixture.customerId()))));
        assertThat(preview.at("/content").asText()).contains("共10次", "已使用4次", "还剩6次");
        assertThat(preview.at("/canSend").asBoolean()).isTrue();
        assertThat(preview.at("/missingVariables").isEmpty()).isTrue();
        assertThat(preview.at("/variables/customer_name").asText()).contains("快捷测试客户");

        long appointmentTemplate = templateId("APPOINTMENT_CONFIRM");
        JsonNode missing = data(admin.withAuth(post("/api/v1/quick-replies/" + appointmentTemplate + "/preview"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", fixture.customerId()))));
        assertThat(missing.at("/canSend").asBoolean()).isFalse();
        assertThat(missing.at("/missingVariables").toString()).contains("appointment_date", "appointment_time",
                "institution_name");

        mockMvc.perform(post("/api/v1/quick-replies/" + templateId + "/preview")
                        .cookie(admin.cookies()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/quick-replies").cookie(login("inst1").cookies()))
                .andExpect(status().isForbidden());
        AuthCookies otherTeam = login("manager2");
        mockMvc.perform(otherTeam.withAuth(post("/api/v1/quick-replies/" + templateId + "/preview"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void templateAndCategoryManagementIsVersionedAuditedAndIdempotent() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = suffix().toUpperCase();
        Map<String, Object> categoryBody = Map.of("name", "测试话术分类", "code", "TEST_" + suffix,
                "scopeType", "GLOBAL", "sortNo", 99, "reason", "新增测试分类");
        JsonNode category = data(admin.withAuth(post("/api/v1/quick-replies/categories"))
                .header("Idempotency-Key", "category-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(categoryBody)));
        long categoryId = category.at("/id").asLong();
        JsonNode categoryReplay = data(admin.withAuth(post("/api/v1/quick-replies/categories"))
                .header("Idempotency-Key", "category-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(categoryBody)));
        assertThat(categoryReplay.at("/id").asLong()).isEqualTo(categoryId);

        Map<String, Object> templateBody = templateBody(categoryId, "TEST_TEMPLATE_" + suffix,
                "您好，感谢您的反馈。", null);
        JsonNode template = data(admin.withAuth(post("/api/v1/quick-replies"))
                .header("Idempotency-Key", "template-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(templateBody)));
        long templateId = template.at("/id").asLong();
        JsonNode templateReplay = data(admin.withAuth(post("/api/v1/quick-replies"))
                .header("Idempotency-Key", "template-create-" + suffix)
                .contentType(MediaType.APPLICATION_JSON).content(json(templateBody)));
        assertThat(templateReplay.at("/id").asLong()).isEqualTo(templateId);

        data(admin.withAuth(patch("/api/v1/quick-replies/" + templateId))
                .header("Idempotency-Key", "template-update-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(templateBody(categoryId, "TEST_TEMPLATE_" + suffix,
                        "您好，感谢您的耐心等待。", 0L))));
        JsonNode disabled = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId + "/disable"))
                .header("Idempotency-Key", "template-disable-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "reason", "停用测试话术"))));
        assertThat(disabled.at("/status").asText()).isEqualTo("DISABLED");
        data(admin.withAuth(patch("/api/v1/quick-replies/categories/" + categoryId))
                .header("Idempotency-Key", "category-update-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(categoryBody("测试分类改名", "TEST_" + suffix, 0L))));
        JsonNode disabledCategory = data(admin.withAuth(post("/api/v1/quick-replies/categories/" + categoryId + "/disable"))
                .header("Idempotency-Key", "category-disable-" + suffix)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", 1, "reason", "停用测试分类"))));
        assertThat(disabledCategory.at("/status").asText()).isEqualTo("DISABLED");

        mockMvc.perform(admin.withAuth(post("/api/v1/quick-replies"))
                        .header("Idempotency-Key", "template-duplicate-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(templateBody(categoryId,
                                "TEST_TEMPLATE_" + suffix, "重复编码", null))))
                .andExpect(status().isConflict());
        mockMvc.perform(login("lead1").withAuth(post("/api/v1/quick-replies/categories"))
                        .header("Idempotency-Key", "category-forbidden-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(json(categoryBody)))
                .andExpect(status().isForbidden());
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE target_type='QUICK_REPLY_TEMPLATE' "
                + "AND target_id=?", Long.toString(templateId))).isEqualTo(3);
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE target_type='QUICK_REPLY_CATEGORY' "
                + "AND target_id=?", Long.toString(categoryId))).isEqualTo(3);
    }

    @Test
    void usageIsMaskedIdempotentAndAppendOnly() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        long templateId = templateId("PROJECT_REMAINING");
        String original = "测试客户您好，还剩6次。";
        Map<String, Object> body = Map.of("customerId", fixture.customerId(), "externalUserid", fixture.externalUserid(),
                "scene", "PACKAGE_REMAINING", "originalContent", original,
                "finalContent", original + "联系电话13800138000", "edited", true,
                "sendStatus", "SDK_SUCCESS", "clientResult", "sendChatMessage:ok");
        String key = "usage-" + suffix();
        JsonNode created = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId + "/usage"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(created.at("/sendStatus").asText()).isEqualTo("SDK_SUCCESS");
        assertThat(created.at("/finalContent").asText()).contains("138****8000").doesNotContain("13800138000");
        JsonNode replay = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId + "/usage"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(replay.at("/id").asLong()).isEqualTo(created.at("/id").asLong());
        assertThat(queryLong("SELECT COUNT(*) FROM crm_quick_reply_usage WHERE tenant_id=1 AND idempotency_key=?", key))
                .isEqualTo(1);
        JsonNode usages = data(get("/api/v1/quick-replies/usages").cookie(admin.cookies()));
        assertThat(usages.at("/total").asLong()).isPositive();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_quick_reply_usage SET send_status='COPIED' WHERE id=?",
                created.at("/id").asLong())).hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_quick_reply_usage WHERE id=?",
                created.at("/id").asLong())).hasMessageContaining("append only");
    }

    private Map<String, Object> templateBody(long categoryId, String code, String content, Long version) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", "测试话术");
        body.put("code", code);
        body.put("messageType", "TEXT");
        body.put("content", content);
        body.put("scene", "FOLLOWUP");
        body.put("scopeType", "GLOBAL");
        body.put("allowEdit", true);
        body.put("requirePreview", true);
        body.put("aiEnabled", false);
        body.put("sortNo", 1);
        body.put("reason", "维护测试话术");
        if (version != null) {
            body.put("version", version);
        }
        return body;
    }

    private Map<String, Object> categoryBody(String name, String code, long version) {
        return Map.of("name", name, "code", code, "scopeType", "GLOBAL", "sortNo", 100,
                "version", version, "reason", "修改测试分类");
    }

    private Fixture newFixture() {
        String suffix = suffix();
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'快捷测试客户')", "QUICK-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "QUICK-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,status,receiver_type,ordered_at) VALUES (1,?,?,2,2,'CRM','REGULAR',"
                + "'PAID','PLATFORM',CURRENT_TIMESTAMP(3))", "QUICK-ORDER-" + suffix, customerId);
        long orderId = queryLong("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?", "QUICK-ORDER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,"
                + "purchase_quantity) VALUES (1,?,1,1,'测试护理包',1)", orderId);
        long orderItemId = queryLong("SELECT id FROM crm_order_item WHERE tenant_id=1 AND order_id=?", orderId);
        jdbcTemplate.update("INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,granted_count,"
                + "available_count,version_no,valid_from,valid_to,status) VALUES (1,?,?,101,10,6,1,CURRENT_DATE,"
                + "DATE_ADD(CURRENT_DATE,INTERVAL 180 DAY),'AVAILABLE')", customerId, orderItemId);
        String external = "QUICK-EXTERNAL-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001',?,2,?,'GRANTED','ACTIVE')", external, customerId);
        return new Fixture(customerId, external);
    }

    private long templateId(String code) {
        return queryLong("SELECT id FROM crm_quick_reply_template WHERE tenant_id=1 AND code=?", code);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private String json(Map<String, ?> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record Fixture(long customerId, String externalUserid) {
    }
}
