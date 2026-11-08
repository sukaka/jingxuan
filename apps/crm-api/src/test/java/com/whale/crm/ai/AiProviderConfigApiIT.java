package com.whale.crm.ai;

import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiProviderConfigApiIT extends AbstractMysqlIT {
    private static final String API_KEY = "sk-integration-synthetic-only";
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AesGcmCipher cipher;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id=1 AND scope_code LIKE 'ai.provider.config.%'");
        jdbcTemplate.update("DELETE FROM crm_ai_provider_config WHERE tenant_id=1");
    }

    @Test
    void adminCanSaveWithoutKeyEchoAndReplayCannotAdvanceVersion() throws Exception {
        AuthCookies admin = login("admin");
        String key = key("save");
        Map<String, Object> body = body(0L);
        String response = mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.apiKeyCipher").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(API_KEY);
        String encrypted = jdbcTemplate.queryForObject(
                "SELECT api_key_cipher FROM crm_ai_provider_config WHERE tenant_id=1", String.class);
        assertThat(encrypted).isNotEqualTo(API_KEY);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(API_KEY);
        String audit = jdbcTemplate.queryForObject("SELECT CONCAT_WS('',before_json,after_json) FROM crm_audit_log "
                + "WHERE tenant_id=1 AND action_code='AI_PROVIDER_CONFIG_SAVE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(audit).doesNotContain(API_KEY, encrypted);

        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0));
        assertThat(version()).isZero();

        Map<String, Object> changedBody = new java.util.HashMap<>(body);
        changedBody.put("model", "different-model");
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changedBody)))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key("advance")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key("stale")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAdminRoleHasConfigPermission() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/ai/config").cookie(admin.cookies())).andExpect(status().isOk());
        for (String username : new String[]{"owner1", "lead1", "manager1", "business1", "finance1", "operator1"}) {
            AuthCookies actor = login(username);
            mockMvc.perform(get("/api/v1/ai/config").cookie(actor.cookies()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        AuthCookies manager = login("manager1");
        mockMvc.perform(manager.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", key("forbidden-save"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(0L))))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/ai/config/test"))
                        .header("Idempotency-Key", key("forbidden-test")))
                .andExpect(status().isForbidden());
    }

    private Map<String, Object> body(long version) {
        return Map.of("provider", "openai_compatible", "baseUrl", "https://provider.example.com/v1",
                "apiKey", API_KEY, "model", "synthetic-model", "timeoutSeconds", 30, "enabled", true, "version", version);
    }

    private long version() {
        Long value = jdbcTemplate.queryForObject("SELECT version FROM crm_ai_provider_config WHERE tenant_id=1", Long.class);
        return value == null ? -1 : value;
    }

    private String key(String prefix) {
        return "it-ai-config-" + prefix + "-" + UUID.randomUUID();
    }
}
