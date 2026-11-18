package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WecomH5CardApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cardSnapshotIsPublicWithoutInternalIdsAndUsageRemainsAuditableAfterDisable() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        String createKey = key("create");
        Map<String, Object> body = createBody(fixture);
        JsonNode card = data(admin.withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        long id = card.at("/id").asLong();
        assertThat(card.at("/title").asText()).contains("138****8000").doesNotContain("13800138000");
        assertThat(card.at("/link").asText()).startsWith("http://localhost:5173/h5/cards/");
        assertThat(card.at("/imageUrl").asText()).isEqualTo("http://localhost:5173/h5-card-cover.svg");
        assertThat(card.at("/status").asText()).isEqualTo("ACTIVE");
        String shareKey = card.at("/link").asText().substring(card.at("/link").asText().lastIndexOf('/') + 1);
        assertThat(shareKey).hasSize(43);

        JsonNode replay = data(admin.withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(replay.at("/id").asLong()).isEqualTo(id);
        assertThat(replay.at("/link").asText()).isEqualTo(card.at("/link").asText());
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("summary", "不同请求");
        mockMvc.perform(admin.withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", createKey)
                        .contentType(MediaType.APPLICATION_JSON).content(json(changed)))
                .andExpect(status().isConflict());

        String publicBody = mockMvc.perform(get("/api/v1/public/h5-cards/" + shareKey))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode publicCard = objectMapper.readTree(publicBody).path("data");
        assertThat(publicCard.at("/content").asText()).contains("公开介绍");
        assertThat(publicBody).doesNotContain("customerId", "employeeId", "tenantId", "externalUserid",
                "external-");
        assertThat(publicBody).doesNotContain("13800138000");
        JsonNode history = data(get("/api/v1/h5/cards").param("customerId", Long.toString(fixture.customerId()))
                .cookie(admin.cookies()));
        assertThat(history).hasSize(1);

        mockMvc.perform(post("/api/v1/h5/cards").cookie(admin.cookies()).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key("csrf")).content(json(body)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(login("inst1").withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", key("forbidden"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/h5/cards/" + id + "/disable").cookie(admin.cookies()))
                .andExpect(status().isNotFound());

        String disableKey = key("disable");
        Map<String, Object> disableBody = Map.of("version", 0, "reason", "停止公开此介绍");
        JsonNode disabled = data(admin.withAuth(post("/api/v1/h5/cards/" + id + "/disable"))
                .header("Idempotency-Key", disableKey).contentType(MediaType.APPLICATION_JSON).content(json(disableBody)));
        assertThat(disabled.at("/status").asText()).isEqualTo("DISABLED");
        assertThat(disabled.at("/version").asLong()).isEqualTo(1);
        JsonNode disableReplay = data(admin.withAuth(post("/api/v1/h5/cards/" + id + "/disable"))
                .header("Idempotency-Key", disableKey).contentType(MediaType.APPLICATION_JSON).content(json(disableBody)));
        assertThat(disableReplay.at("/version").asLong()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/public/h5-cards/" + shareKey)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/public/h5-cards/not-a-valid-key")).andExpect(status().isBadRequest());

        Map<String, Object> usageBody = Map.of("customerId", fixture.customerId(), "sendStatus", "SDK_UNKNOWN",
                "clientResult", "timeout 13800138000");
        String usageKey = key("usage");
        JsonNode usage = data(admin.withAuth(post("/api/v1/h5/cards/" + id + "/usage"))
                .header("Idempotency-Key", usageKey).contentType(MediaType.APPLICATION_JSON).content(json(usageBody)));
        assertThat(usage.at("/sendStatus").asText()).isEqualTo("SDK_UNKNOWN");
        assertThat(usage.at("/clientResult").asText()).contains("138****8000").doesNotContain("13800138000");
        JsonNode usageReplay = data(admin.withAuth(post("/api/v1/h5/cards/" + id + "/usage"))
                .header("Idempotency-Key", usageKey).contentType(MediaType.APPLICATION_JSON).content(json(usageBody)));
        assertThat(usageReplay.at("/id").asLong()).isEqualTo(usage.at("/id").asLong());
        assertThat(queryLong("SELECT COUNT(*) FROM crm_wecom_h5_card_usage WHERE tenant_id=1 AND card_id=?", id))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_wecom_h5_card SET title='覆盖快照' WHERE id=?", id))
                .hasMessageContaining("only allows active to disabled");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_wecom_h5_card_usage SET send_status='COPIED' WHERE id=?",
                usage.at("/id").asLong())).hasMessageContaining("append only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_wecom_h5_card_usage WHERE id=?",
                usage.at("/id").asLong())).hasMessageContaining("append only");
        assertThat(queryLong("SELECT COUNT(*) FROM crm_audit_log WHERE target_type='WECOM_H5_CARD' AND target_id=?",
                Long.toString(id))).isEqualTo(2);
    }

    @Test
    void otherEmployeeAndUnsafePublicClaimsAreRejected() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        JsonNode card = data(admin.withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", key("scope-source"))
                .contentType(MediaType.APPLICATION_JSON).content(json(createBody(fixture))));
        AuthCookies manager = login("manager1");
        mockMvc.perform(manager.withAuth(post("/api/v1/h5/cards/" + card.at("/id").asLong() + "/disable"))
                        .header("Idempotency-Key", key("other-employee")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "reason", "无权处理"))))
                .andExpect(status().isNotFound());
        Map<String, Object> unsafe = createBody(fixture);
        unsafe.put("content", "保证治愈，百分百有效");
        mockMvc.perform(admin.withAuth(post("/api/v1/h5/cards")).header("Idempotency-Key", key("unsafe"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(unsafe)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private Map<String, Object> createBody(Fixture fixture) {
        return new LinkedHashMap<>(Map.of("customerId", fixture.customerId(), "projectId", 101,
                "externalUserid", fixture.externalUserid(), "title", "项目介绍 13800138000",
                "summary", "了解项目服务流程、机构安排与预约方式", "content", "公开介绍：可了解服务流程与注意事项，具体以人工沟通为准。"));
    }

    private Fixture newFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'H5测试客户')", "H5-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "H5-CUSTOMER-" + suffix);
        String external = "H5-EXTERNAL-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001',?,2,?,'GRANTED','ACTIVE')", external, customerId);
        return new Fixture(customerId, external);
    }

    private long queryLong(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder) throws Exception {
        String body = mockMvc.perform(builder).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "it-h5-card-" + prefix + "-" + UUID.randomUUID();
    }

    private record Fixture(long customerId, String externalUserid) {
    }
}
