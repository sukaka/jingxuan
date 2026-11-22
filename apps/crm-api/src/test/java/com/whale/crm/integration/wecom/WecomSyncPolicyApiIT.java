package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.outbox.relay.enabled=false", "app.scheduling.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WecomSyncPolicyApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    private AuthCookies admin;
    private AuthCookies manager;
    private long maxSyncRunIdBeforeTest;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager = login("manager1");
    }

    @BeforeEach
    void cleanBefore() {
        cleanupSyncPolicy();
        Long maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM crm_wecom_contact_sync_run WHERE tenant_id=1 AND corp_id='MOCK-CORP-001'",
                Long.class);
        maxSyncRunIdBeforeTest = maxId != null ? maxId : 0L;
    }

    @AfterEach
    void cleanAfter() {
        cleanupSyncPolicy();
        jdbcTemplate.update(
                "DELETE FROM crm_wecom_contact_sync_run WHERE tenant_id=1 AND corp_id='MOCK-CORP-001' AND id>?",
                maxSyncRunIdBeforeTest);
    }

    private void cleanupSyncPolicy() {
        jdbcTemplate.update("DELETE FROM crm_wecom_sync_policy WHERE tenant_id=1 AND corp_id='MOCK-CORP-001'");
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id=1 AND scope_code='wecom.sync.policy'");
    }

    @Test
    void policyIsPersistedAndWriteRequiresMonitorPermission() throws Exception {
        String nextRun = "2026-11-01T08:30:00Z";
        JsonNode saved = data(admin.withAuth(put("/api/v1/integrations/wecom/sync-policy")
                .param("corpId", "MOCK-CORP-001")
                .header("Idempotency-Key", "IT-WECOM-POLICY-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("enabled", true, "contactSyncEnabled", true,
                        "archiveSyncEnabled", true, "intervalSeconds", 300, "nextRunAt", nextRun)))));
        assertThat(saved.at("/nextRunAt").asText()).isEqualTo(nextRun);
        assertThat(saved.at("/contactSyncEnabled").asBoolean()).isTrue();
        assertThat(saved.at("/archiveSyncEnabled").asBoolean()).isTrue();

        JsonNode read = data(manager.withAuth(get("/api/v1/integrations/wecom/sync-policy")
                .param("corpId", "MOCK-CORP-001")));
        assertThat(read.at("/intervalSeconds").asInt()).isEqualTo(300);

        mockMvc.perform(manager.withAuth(put("/api/v1/integrations/wecom/sync-policy")
                        .param("corpId", "MOCK-CORP-001")
                        .header("Idempotency-Key", "IT-WECOM-POLICY-DENIED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "contactSyncEnabled", true,
                                "archiveSyncEnabled", false, "intervalSeconds", 300, "nextRunAt", nextRun)))))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/wecom/sync-policy")
                        .param("corpId", "MOCK-CORP-001")
                        .header("Idempotency-Key", "IT-WECOM-POLICY-INVALID")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true, "contactSyncEnabled", false,
                                "archiveSyncEnabled", false, "intervalSeconds", 300, "nextRunAt", nextRun)))))
                .andExpect(status().isBadRequest());
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }
}
