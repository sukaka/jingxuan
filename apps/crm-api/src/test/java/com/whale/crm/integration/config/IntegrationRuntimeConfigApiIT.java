package com.whale.crm.integration.config;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IntegrationRuntimeConfigApiIT extends AbstractMysqlIT {
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id IN (1,2) "
                + "AND scope_code='integration.runtime.config.save'");
        jdbcTemplate.update("DELETE FROM crm_integration_runtime_config WHERE tenant_id IN (1,2)");
    }

    @Test
    void adminCanSaveImmediatelyWithAuditVersionAndIdempotentReplay() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/integrations/runtime-config").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.data.version").value(0));

        String key = key("save");
        Map<String, Object> body = body(true, false, true, 0L);
        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/runtime-config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("DATABASE"))
                .andExpect(jsonPath("$.data.youzanRealEnabled").value(true))
                .andExpect(jsonPath("$.data.wecomArchiveRealEnabled").value(false))
                .andExpect(jsonPath("$.data.wecomOfficialEnabled").value(true));
        assertThat(jdbcTemplate.queryForObject("SELECT youzan_real_enabled FROM crm_integration_runtime_config "
                + "WHERE tenant_id=1", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='INTEGRATION_RUNTIME_CONFIG_SAVE'", Long.class)).isEqualTo(1L);

        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/runtime-config"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0));
        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/runtime-config"))
                        .header("Idempotency-Key", key("update")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(false, false, false, 0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/runtime-config"))
                        .header("Idempotency-Key", key("stale")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(true, true, true, 0L))))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyRuntimeAdminCanReadOrWriteAndCsrfIsRequired() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead = login("lead1");
        mockMvc.perform(get("/api/v1/integrations/runtime-config").cookie(lead.cookies()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/integrations/runtime-config"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/integrations/runtime-config").cookie(admin.cookies())
                        .header("Idempotency-Key", key("csrf")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(true, true, true, 0L))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(lead.withAuth(put("/api/v1/integrations/runtime-config"))
                        .header("Idempotency-Key", key("forbidden")).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body(true, true, true, 0L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantDatabaseConfigDoesNotLeak() throws Exception {
        jdbcTemplate.update("INSERT INTO crm_integration_runtime_config "
                + "(tenant_id,youzan_real_enabled,wecom_archive_real_enabled,wecom_official_enabled) "
                + "VALUES (2,TRUE,TRUE,TRUE)");
        AuthCookies admin = login("admin");

        mockMvc.perform(get("/api/v1/integrations/runtime-config").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.data.youzanRealEnabled").value(false))
                .andExpect(jsonPath("$.data.wecomArchiveRealEnabled").value(false))
                .andExpect(jsonPath("$.data.wecomOfficialEnabled").value(false));
    }

    private static Map<String, Object> body(boolean youzan, boolean archive, boolean official, long version) {
        return Map.of("youzanRealEnabled", youzan, "wecomArchiveRealEnabled", archive,
                "wecomOfficialEnabled", official, "version", version);
    }

    private static String key(String prefix) {
        return "it-integration-runtime-" + prefix + "-" + UUID.randomUUID();
    }
}
