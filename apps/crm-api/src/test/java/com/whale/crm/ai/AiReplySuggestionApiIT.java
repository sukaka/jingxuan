package com.whale.crm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.ai.application.AiWorkerException;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiReplySuggestionApiIT extends AbstractMysqlIT {
    @MockitoBean
    private AiWorkerClient workerClient;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AesGcmCipher cipher;

    @BeforeEach
    void mockReplies() {
        when(workerClient.generateReply(anyString(), anyLong(), anyString(), anyMap(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class))).thenAnswer(invocation -> {
            Map<String, Object> facts = invocation.getArgument(3);
            String scene = invocation.getArgument(2);
            String name = String.valueOf(facts.getOrDefault("customer_name", "测试客户"));
            if (!"PACKAGE_REMAINING".equals(scene)) {
                return result(scene, name + "您好，想了解您最近的服务感受。", List.of("customer_name"));
            }
            String reply = name + "您好，您的" + facts.get("project_name") + "套餐共" + facts.get("total_count")
                    + "次，已使用" + facts.get("used_count") + "次，还剩" + facts.get("remaining_count") + "次。";
            return result(scene, reply, List.of("customer_name", "project_name", "total_count", "used_count",
                    "remaining_count"));
        });
    }

    @Test
    void generationUsesMaskedServerFactsIsEncryptedAndIdempotent() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        String key = key("generate");
        Map<String, Object> body = Map.of("customerId", fixture.customerId(), "templateId", templateId(),
                "instruction", "语气温和");
        JsonNode generated = data(admin.withAuth(post("/api/v1/ai/replies/generate"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(generated.at("/status").asText()).isEqualTo("PROPOSED");
        assertThat(generated.at("/provider").asText()).isEqualTo("mock");
        assertThat(generated.at("/reply").asText()).contains("共10次", "已使用4次", "还剩6次");
        assertThat(generated.at("/accepted").asBoolean()).isFalse();
        assertThat(generated.at("/usedFactCodes").toString()).contains("remaining_count");

        org.mockito.ArgumentCaptor<Map<String, Object>> facts = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(workerClient).generateReply(anyString(), org.mockito.ArgumentMatchers.eq(fixture.customerId()),
                org.mockito.ArgumentMatchers.eq("PACKAGE_REMAINING"), facts.capture(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class));
        assertThat(facts.getValue()).containsEntry("remaining_count", 6L);
        assertThat(facts.getValue().get("order_no").toString()).contains("138****8000")
                .doesNotContain("13800138000");
        String encrypted = jdbcTemplate.queryForObject("SELECT reply_cipher FROM crm_ai_reply_suggestion WHERE id=?",
                String.class, generated.at("/id").asLong());
        assertThat(encrypted).isNotEqualTo(generated.at("/reply").asText());
        assertThat(cipher.decrypt(encrypted)).isEqualTo(generated.at("/reply").asText());
        String snapshot = jdbcTemplate.queryForObject(
                "SELECT facts_snapshot_json FROM crm_ai_reply_suggestion WHERE id=?", String.class,
                generated.at("/id").asLong());
        assertThat(snapshot).contains("138****8000").doesNotContain("13800138000");

        JsonNode replay = data(admin.withAuth(post("/api/v1/ai/replies/generate"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertThat(replay.at("/id").asLong()).isEqualTo(generated.at("/id").asLong());
        verify(workerClient, times(1)).generateReply(anyString(), anyLong(), anyString(), anyMap(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class));
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("instruction", "另一种要求");
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/replies/generate")).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json(changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        JsonNode history = data(get("/api/v1/ai/replies").param("customerId", Long.toString(fixture.customerId()))
                .cookie(admin.cookies()));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).at("/id").asLong()).isEqualTo(generated.at("/id").asLong());
    }

    @Test
    void humanDecisionControlsUsageAndPreservesFinalText() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        JsonNode generated = generate(admin, fixture, key("decision-source"));
        long id = generated.at("/id").asLong();
        String original = generated.at("/reply").asText();
        String edited = original + " 联系电话13800138000";
        Map<String, Object> usage = usage(fixture, id, original, edited);
        mockMvc.perform(admin.withAuth(post("/api/v1/quick-replies/" + templateId() + "/usage"))
                        .header("Idempotency-Key", key("before-accept")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(usage)))
                .andExpect(status().isBadRequest());

        Map<String, Object> decision = Map.of("decision", "ACCEPT", "version", 0, "finalContent", edited,
                "reason", "已人工核对客户与事实");
        String decisionKey = key("accept");
        JsonNode accepted = data(admin.withAuth(post("/api/v1/ai/replies/" + id + "/decision"))
                .header("Idempotency-Key", decisionKey).contentType(MediaType.APPLICATION_JSON).content(json(decision)));
        assertThat(accepted.at("/status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.at("/accepted").asBoolean()).isTrue();
        assertThat(accepted.at("/edited").asBoolean()).isTrue();
        assertThat(accepted.at("/finalContent").asText()).isEqualTo(edited);
        JsonNode replay = data(admin.withAuth(post("/api/v1/ai/replies/" + id + "/decision"))
                .header("Idempotency-Key", decisionKey).contentType(MediaType.APPLICATION_JSON).content(json(decision)));
        assertThat(replay.at("/version").asLong()).isEqualTo(1);
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/replies/" + id + "/decision"))
                        .header("Idempotency-Key", key("stale")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(decision)))
                .andExpect(status().isConflict());

        String usageKey = key("usage");
        JsonNode logged = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId() + "/usage"))
                .header("Idempotency-Key", usageKey).contentType(MediaType.APPLICATION_JSON).content(json(usage)));
        assertThat(logged.at("/aiSuggestionId").asLong()).isEqualTo(id);
        assertThat(logged.at("/finalContent").asText()).contains("138****8000").doesNotContain("13800138000");
        assertThat(logged.at("/edited").asBoolean()).isTrue();
        JsonNode usageReplay = data(admin.withAuth(post("/api/v1/quick-replies/" + templateId() + "/usage"))
                .header("Idempotency-Key", usageKey).contentType(MediaType.APPLICATION_JSON).content(json(usage)));
        assertThat(usageReplay.at("/id").asLong()).isEqualTo(logged.at("/id").asLong());
        String storedFinal = jdbcTemplate.queryForObject(
                "SELECT final_content_cipher FROM crm_ai_reply_suggestion WHERE id=?", String.class, id);
        assertThat(cipher.decrypt(storedFinal)).isEqualTo(edited);
        String audit = jdbcTemplate.queryForObject("SELECT after_json FROM crm_audit_log WHERE target_type='AI_REPLY_SUGGESTION' "
                + "AND target_id=? AND action_code='AI_REPLY_ACCEPTED'", String.class, Long.toString(id));
        assertThat(audit).doesNotContain(edited, "13800138000", storedFinal);

        Map<String, Object> mismatched = new LinkedHashMap<>(usage);
        mismatched.put("finalContent", original);
        mockMvc.perform(admin.withAuth(post("/api/v1/quick-replies/" + templateId() + "/usage"))
                        .header("Idempotency-Key", key("mismatch")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(mismatched)))
                .andExpect(status().isBadRequest());

        JsonNode second = generate(admin, fixture, key("reject-source"));
        JsonNode rejected = data(admin.withAuth(post("/api/v1/ai/replies/" + second.at("/id").asLong() + "/decision"))
                .header("Idempotency-Key", key("reject")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("decision", "REJECT", "version", 0, "reason", "内容不适合当前客户"))));
        assertThat(rejected.at("/status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.at("/accepted").asBoolean()).isFalse();
        mockMvc.perform(admin.withAuth(post("/api/v1/quick-replies/" + templateId() + "/usage"))
                        .header("Idempotency-Key", key("rejected-usage")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(usage(fixture, second.at("/id").asLong(), second.at("/reply").asText(),
                                second.at("/reply").asText()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void employeeOwnershipDataScopeAndCsrfAreEnforced() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        JsonNode generated = generate(admin, fixture, key("scope-source"));
        long id = generated.at("/id").asLong();
        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/ai/replies/" + id).cookie(manager.cookies())).andExpect(status().isNotFound());
        mockMvc.perform(manager.withAuth(post("/api/v1/ai/replies/" + id + "/decision"))
                        .header("Idempotency-Key", key("other-employee")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("decision", "REJECT", "version", 0, "reason", "无权处理"))))
                .andExpect(status().isNotFound());
        AuthCookies otherTeam = login("manager2");
        mockMvc.perform(otherTeam.withAuth(post("/api/v1/ai/replies/generate"))
                        .header("Idempotency-Key", key("other-scope")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(login("inst1").withAuth(post("/api/v1/ai/replies/generate"))
                        .header("Idempotency-Key", key("forbidden")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/ai/replies/generate").cookie(admin.cookies())
                        .header("Idempotency-Key", key("csrf")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void fabricatedNumbersUnsafeClaimsAndProviderFailuresAreNotPersisted() throws Exception {
        AuthCookies admin = login("admin");
        Fixture fixture = newFixture();
        long before = countSuggestions();
        when(workerClient.generateReply(anyString(), anyLong(), anyString(), anyMap(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class))).thenReturn(result("PACKAGE_REMAINING",
                "测试客户您好，还剩99次。", List.of("remaining_count")));
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/replies/generate"))
                        .header("Idempotency-Key", key("fake-number")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AI_PROVIDER_FAILED"));
        when(workerClient.generateReply(anyString(), anyLong(), anyString(), anyMap(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class))).thenReturn(result("PACKAGE_REMAINING",
                "已为您预约成功。", List.of()));
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/replies/generate"))
                        .header("Idempotency-Key", key("unsafe-claim")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isBadGateway());
        when(workerClient.generateReply(anyString(), anyLong(), anyString(), anyMap(), anyString(),
                nullable(AiWorkerClient.ProviderConfig.class))).thenThrow(new AiWorkerException("AI 模型服务未启用", true));
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/replies/generate"))
                        .header("Idempotency-Key", key("provider-disabled")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_PROVIDER_DISABLED"));
        assertThat(countSuggestions()).isEqualTo(before);
    }

    private AiWorkerClient.ReplyGenerationResult result(String scene, String reply, List<String> factCodes) {
        return new AiWorkerClient.ReplyGenerationResult("mock", "mock-reply-rules-v1", "reply-v1",
                new AiWorkerClient.ReplySuggestionData(scene, reply, "基于服务端只读事实", new BigDecimal("0.86"),
                        factCodes, "UNVERIFIED", false, true));
    }

    private JsonNode generate(AuthCookies actor, Fixture fixture, String key) throws Exception {
        return data(actor.withAuth(post("/api/v1/ai/replies/generate")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("customerId", fixture.customerId(), "templateId", templateId()))));
    }

    private Map<String, Object> usage(Fixture fixture, long suggestionId, String original, String finalContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", fixture.customerId());
        body.put("externalUserid", fixture.externalUserid());
        body.put("scene", "PACKAGE_REMAINING");
        body.put("originalContent", original);
        body.put("finalContent", finalContent);
        body.put("edited", !original.equals(finalContent));
        body.put("sendStatus", "SDK_SUCCESS");
        body.put("clientResult", "sendChatMessage:ok");
        body.put("aiSuggestionId", suggestionId);
        return body;
    }

    private Fixture newFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                + "VALUES (1,2,1,?,'AI测试客户')", "AI-CUSTOMER-" + suffix);
        long customerId = queryLong("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                "AI-CUSTOMER-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,status,receiver_type,ordered_at) VALUES (1,?,?,2,2,'CRM','REGULAR',"
                + "'PAID','PLATFORM',CURRENT_TIMESTAMP(3))", "AI-ORDER-13800138000-" + suffix, customerId);
        long orderId = queryLong("SELECT id FROM crm_order WHERE tenant_id=1 AND customer_id=?", customerId);
        jdbcTemplate.update("INSERT INTO crm_order_item (tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,"
                + "purchase_quantity) VALUES (1,?,1,1,'测试护理包',1)", orderId);
        long orderItemId = queryLong("SELECT id FROM crm_order_item WHERE tenant_id=1 AND order_id=?", orderId);
        jdbcTemplate.update("INSERT INTO crm_entitlement (tenant_id,customer_id,order_item_id,project_id,granted_count,"
                + "available_count,version_no,valid_from,valid_to,status) VALUES (1,?,?,101,10,6,1,CURRENT_DATE,"
                + "DATE_ADD(CURRENT_DATE,INTERVAL 180 DAY),'AVAILABLE')", customerId, orderItemId);
        String external = "AI-EXTERNAL-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status,follow_status) VALUES (1,'MOCK-CORP-001',?,2,?,'GRANTED','ACTIVE')", external, customerId);
        return new Fixture(customerId, external);
    }

    private long templateId() {
        return queryLong("SELECT id FROM crm_quick_reply_template WHERE tenant_id=1 AND code='PROJECT_REMAINING'");
    }

    private long countSuggestions() {
        return queryLong("SELECT COUNT(*) FROM crm_ai_reply_suggestion WHERE tenant_id=1");
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
        return "it-ai-reply-" + prefix + "-" + UUID.randomUUID();
    }

    private record Fixture(long customerId, String externalUserid) {
    }
}
