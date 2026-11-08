package com.whale.crm.ai;

import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DifyKnowledgeConfigApiIT extends AbstractMysqlIT {
    private static final String API_KEY = "dify-integration-synthetic-only";
    private static final String DATASET_API_KEY = "dify-dataset-integration-synthetic-only";
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AesGcmCipher cipher;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id IN (1,2) AND scope_code LIKE 'ai.knowledge.config.%'");
        jdbcTemplate.update("DELETE FROM crm_dify_knowledge_config WHERE tenant_id IN (1,2)");
    }

    @Test
    void adminCanSaveWithoutSecretEchoAndReplayCannotAdvanceVersion() throws Exception {
        AuthCookies admin = login("admin");
        String key = key("save");
        Map<String, Object> body = body(0L);
        String response = mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.datasetId").value("dataset-it-1"))
                .andExpect(jsonPath("$.data.datasetApiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data.syncEnabled").value(true))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.appApiKeyCipher").doesNotExist())
                .andExpect(jsonPath("$.data.datasetApiKey").doesNotExist())
                .andExpect(jsonPath("$.data.datasetApiKeyCipher").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(API_KEY, DATASET_API_KEY);
        String encrypted = jdbcTemplate.queryForObject(
                "SELECT app_api_key_cipher FROM crm_dify_knowledge_config WHERE tenant_id=1", String.class);
        String datasetEncrypted = jdbcTemplate.queryForObject(
                "SELECT dataset_api_key_cipher FROM crm_dify_knowledge_config WHERE tenant_id=1", String.class);
        assertThat(encrypted).isNotEqualTo(API_KEY);
        assertThat(datasetEncrypted).isNotEqualTo(DATASET_API_KEY);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(API_KEY);
        assertThat(cipher.decrypt(datasetEncrypted)).isEqualTo(DATASET_API_KEY);
        String audit = jdbcTemplate.queryForObject("SELECT CONCAT_WS('',before_json,after_json) FROM crm_audit_log "
                + "WHERE tenant_id=1 AND action_code='DIFY_KNOWLEDGE_CONFIG_SAVE' ORDER BY id DESC LIMIT 1", String.class);
        assertThat(audit).doesNotContain(API_KEY, DATASET_API_KEY, encrypted, datasetEncrypted);

        mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        assertThat(version(1)).isZero();

        Map<String, Object> changed = new HashMap<>(body);
        changed.put("timeoutSeconds", 45);
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                        .header("Idempotency-Key", key("advance")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                        .header("Idempotency-Key", key("stale")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyAdminHasPermissionAndCsrfIsRequired() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/ai/knowledge/config").cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.provider").value("disabled"));
        for (String username : new String[]{"owner1", "lead1", "manager1", "business1", "finance1", "operator1"}) {
            AuthCookies actor = login(username);
            mockMvc.perform(get("/api/v1/ai/knowledge/config").cookie(actor.cookies()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        AuthCookies manager = login("manager1");
        mockMvc.perform(manager.withAuth(post("/api/v1/ai/knowledge/config/test"))
                        .header("Idempotency-Key", key("forbidden-test")))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/ai/knowledge/config").cookie(admin.cookies())
                        .header("Idempotency-Key", key("missing-csrf")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(0L))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void anotherTenantConfigIsNotVisible() throws Exception {
        jdbcTemplate.update("INSERT INTO crm_dify_knowledge_config "
                + "(tenant_id,provider,timeout_seconds,enabled,connection_status) VALUES (2,'mock',30,TRUE,'CONNECTED')");
        AuthCookies admin = login("admin");

        mockMvc.perform(get("/api/v1/ai/knowledge/config").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("disabled"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    private Map<String, Object> body(long version) {
        return Map.of("provider", "dify", "baseUrl", "https://dify.example.com/v1",
                "apiKey", API_KEY, "datasetId", "dataset-it-1", "datasetApiKey", DATASET_API_KEY,
                "timeoutSeconds", 30, "enabled", true, "syncEnabled", true, "version", version);
    }

    private long version(long tenantId) {
        Long value = jdbcTemplate.queryForObject("SELECT version FROM crm_dify_knowledge_config WHERE tenant_id=?",
                Long.class, tenantId);
        return value == null ? -1 : value;
    }

    private String key(String prefix) {
        return "it-dify-knowledge-config-" + prefix + "-" + UUID.randomUUID();
    }
}
