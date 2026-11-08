package com.whale.crm.integration.youzan;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YouzanConnectionApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    private AuthCookies admin;
    private AuthCookies manager;
    private AuthCookies business;

    @BeforeAll
    void setUp() throws Exception {
        admin = login("admin");
        manager = login("manager1");
        business = login("business1");
    }

    @Test
    void shopAndPolicyAreEncryptedTenantProtectedAndConfigurableWithoutRealCredentials() throws Exception {
        String shopId = "IT-YZ-CONFIG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String secret = "IT-FAKE-SECRET-NOT-REAL";
        JsonNode saved = data(admin.withAuth(post("/api/v1/integrations/youzan/shops")
                        .header("Idempotency-Key", "IT-YZ-CONFIG-SAVE-" + shopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "shopName", "虚构配置店",
                                "clientId", "IT-FAKE-CLIENT", "clientSecret", secret, "syncEnabled", false)))));
        assertThat(saved.at("/secretConfigured").asBoolean()).isTrue();
        assertThat(saved.toString()).doesNotContain(secret, "clientSecretCipher", "accessTokenCipher");
        String cipher = jdbcTemplate.queryForObject("SELECT client_secret_cipher FROM crm_youzan_shop_config WHERE tenant_id=1 AND shop_id=?",
                String.class, shopId);
        assertThat(cipher).isNotEqualTo(secret).doesNotContain(secret);

        mockMvc.perform(manager.withAuth(get("/api/v1/integrations/youzan/shops")))
                .andExpect(status().isForbidden());
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/youzan/shops/" + shopId + "/test")
                        .header("Idempotency-Key", "IT-YZ-CONFIG-TEST-" + shopId)))
                .andExpect(status().isUnprocessableEntity());

        JsonNode policy = data(admin.withAuth(put("/api/v1/integrations/youzan/shops/" + shopId + "/sync-policy")
                .header("Idempotency-Key", "IT-YZ-POLICY-SAVE-" + shopId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("initialStartTime", Instant.parse("2026-01-01T00:00:00Z"),
                        "intervalSeconds", 300, "overlapSeconds", 300, "pageSize", 50, "enabled", false,
                        "nextRunAt", Instant.parse("2026-11-01T08:00:00Z"))))));
        assertThat(policy.at("/shopId").asText()).isEqualTo(shopId);
        assertThat(policy.at("/pageSize").asInt()).isEqualTo(50);
        assertThat(policy.at("/nextRunAt").asText()).isEqualTo("2026-11-01T08:00:00Z");
        JsonNode jobs = data(admin.withAuth(get("/api/v1/integrations/youzan/sync-jobs").param("shopId", shopId)));
        assertThat(jobs.at("/total").asInt()).isZero();
    }

    @Test
    void shopLeaseRejectsConcurrentSyncAndIsReleasedAfterCompletion() throws Exception {
        String shopId = "IT-YZ-LEASE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("INSERT INTO crm_youzan_shop_config (tenant_id,shop_id,shop_name,client_id,client_secret_cipher,connection_status,sync_enabled,lease_owner,lease_expires_at) "
                + "VALUES (1,?,'租约测试店','IT-LEASE-CLIENT','cipher','UNVERIFIED',0,'other-owner',DATE_ADD(NOW(3),INTERVAL 1 HOUR))", shopId);

        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/youzan/sync")
                        .header("Idempotency-Key", "IT-LEASE-DENIED-" + shopId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "cursor", "0")))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_youzan_sync_job WHERE tenant_id=1 AND shop_id=?", Integer.class, shopId)).isZero();

        jdbcTemplate.update("UPDATE crm_youzan_shop_config SET lease_owner=NULL,lease_expires_at=NULL WHERE tenant_id=1 AND shop_id=?", shopId);
        JsonNode response = data(admin.withAuth(post("/api/v1/integrations/youzan/sync")
                .header("Idempotency-Key", "IT-LEASE-SUCCESS-" + shopId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("shopId", shopId, "cursor", "0")))));
        assertThat(response.at("/received").asInt()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_youzan_sync_job WHERE tenant_id=1 AND shop_id=?", String.class, shopId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT lease_owner FROM crm_youzan_shop_config WHERE tenant_id=1 AND shop_id=?", String.class, shopId))
                .isNull();
    }

    @Test
    void businessRoleCannotTriggerSyncWithoutIntegrationManagePermission() throws Exception {
        mockMvc.perform(business.withAuth(post("/api/v1/integrations/youzan/sync")
                        .header("Idempotency-Key", "IT-BUSINESS-SYNC-DENIED")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("shopId", "MOCK-SHOP-001", "cursor", "0")))))
                .andExpect(status().isForbidden());
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }
}
