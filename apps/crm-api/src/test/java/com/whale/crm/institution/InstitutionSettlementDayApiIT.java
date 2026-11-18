package com.whale.crm.institution;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InstitutionSettlementDayApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void defaultConfigUsesCsrfIdempotencyAndOptimisticLock() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies operator = login("operator1");
        JsonNode current = data(get("/api/v1/institution-settlement-default").cookie(admin.cookies()), 200);
        long version = current.at("/version").asLong();
        Map<String, Object> body = Map.of("defaultSettlementDay", "15", "version", version);
        String key = key("default-save");

        mockMvc.perform(put("/api/v1/institution-settlement-default").cookie(admin.cookies())
                        .header("Idempotency-Key", key("missing-csrf"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isForbidden());
        JsonNode saved = data(admin.withAuth(put("/api/v1/institution-settlement-default"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)), 200);
        long savedVersion = current.at("/configured").asBoolean() ? version + 1 : 1;
        assertThat(saved.at("/defaultSettlementDay").asText()).isEqualTo("15");
        assertThat(saved.at("/configured").asBoolean()).isTrue();
        assertThat(saved.at("/version").asLong()).isEqualTo(savedVersion);

        JsonNode replayed = data(admin.withAuth(put("/api/v1/institution-settlement-default"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body)), 200);
        assertThat(replayed.at("/version").asLong()).isEqualTo(savedVersion);
        JsonNode advanced = data(admin.withAuth(put("/api/v1/institution-settlement-default"))
                .header("Idempotency-Key", key("advance-default")).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("defaultSettlementDay", "16", "version", savedVersion))), 200);
        long currentVersion = advanced.at("/version").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/institution-settlement-default"))
                        .header("Idempotency-Key", key("stale-default"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultSettlementDay", "20", "version", savedVersion))))
                .andExpect(status().isConflict());
        mockMvc.perform(operator.withAuth(put("/api/v1/institution-settlement-default"))
                        .header("Idempotency-Key", key("institution-default"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultSettlementDay", "20", "version", currentVersion))))
                .andExpect(status().isForbidden());
    }

    @Test
    void firstSavePublishesVersionOneAndSecondStaleClientConflicts() throws Exception {
        AuthCookies admin = login("admin");
        jdbcTemplate.update("DELETE FROM crm_institution_settlement_config WHERE tenant_id=1");
        JsonNode initial = data(get("/api/v1/institution-settlement-default").cookie(admin.cookies()), 200);
        assertThat(initial.at("/configured").asBoolean()).isFalse();
        assertThat(initial.at("/version").asLong()).isZero();

        JsonNode first = data(admin.withAuth(put("/api/v1/institution-settlement-default"))
                .header("Idempotency-Key", key("first-client"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("defaultSettlementDay", "11", "version", 0))), 200);
        assertThat(first.at("/version").asLong()).isEqualTo(1);

        mockMvc.perform(admin.withAuth(put("/api/v1/institution-settlement-default"))
                        .header("Idempotency-Key", key("concurrent-stale-client"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("defaultSettlementDay", "12", "version", 0))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT default_settlement_day FROM crm_institution_settlement_config WHERE tenant_id=1",
                String.class)).isEqualTo("11");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM crm_institution_settlement_config WHERE tenant_id=1", Long.class)).isEqualTo(1);
    }

    @Test
    void institutionSelfServiceUsesDualApprovalAndRejectsCrossInstitutionAccess() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies admin = login("admin");
        AuthCookies finance = login("finance1");
        AuthCookies own = login("operator1");
        AuthCookies other = login("operator2");
        long institutionId = newActiveMonthlyProfile(business, admin, finance, "6");
        long operatorId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=1 AND username='operator1'", Long.class);
        jdbcTemplate.update("INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status) "
                + "VALUES (1,?,?,'OPERATOR','ACTIVE')", institutionId, operatorId);

        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(own.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementDay").value("6"));
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(other.cookies()))
                .andExpect(status().isNotFound());

        JsonNode request = data(own.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", key("own-day"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(changeBody(institutionId, 1, "12"))), 201);
        long requestId = request.at("/id").asLong();
        assertThat(request.at("/currentStep").asText()).isEqualTo("MARKET");
        mockMvc.perform(other.withAuth(post("/api/v1/institution-change-requests"))
                        .header("Idempotency-Key", key("cross-day"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(changeBody(institutionId, 1, "18"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(own.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementDay").value("6"));

        review(admin, requestId, "market-review", 0, key("day-market"));
        review(finance, requestId, "finance-review", 1, key("day-finance"));
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(own.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementDay").value("12"));

        JsonNode reset = data(own.withAuth(post("/api/v1/institution-change-requests"))
                .header("Idempotency-Key", key("reset-day"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"institutionId\":" + institutionId + ",\"fieldCode\":\"settlementDay\","
                        + "\"newValue\":null,\"baseVersion\":2,\"reason\":\"IT-RESTORE-DEFAULT\"}"), 201);
        long resetId = reset.at("/id").asLong();
        review(admin, resetId, "market-review", 0, key("reset-market"));
        review(finance, resetId, "finance-review", 1, key("reset-finance"));
        mockMvc.perform(get("/api/v1/institution-profiles/" + institutionId).cookie(own.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.settlementDay").doesNotExist());
    }

    private long newActiveMonthlyProfile(AuthCookies business, AuthCookies admin, AuthCookies finance,
                                         String settlementDay) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionNo", "IT-DAY-" + UUID.randomUUID());
        body.put("fullName", "结算日合成机构");
        body.put("businessTypeCode", "MED_AESTHETIC");
        body.put("ownershipCode", "PRIVATE");
        body.put("gradeCode", "A");
        body.put("settlementType", "MONTHLY");
        body.put("settlementDay", settlementDay);
        body.put("onlineRate", "0.18");
        body.put("offlineRate", "0.12");
        body.put("platformRate", "0.15");
        JsonNode created = data(business.withAuth(post("/api/v1/institution-profiles"))
                .header("Idempotency-Key", key("create-day-profile"))
                .contentType(MediaType.APPLICATION_JSON).content(json(body)), 201);
        long id = created.at("/id").asLong();
        JsonNode activation = data(business.withAuth(post("/api/v1/institution-profiles/" + id + "/activation-requests"))
                .header("Idempotency-Key", key("activate-day-profile"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("baseVersion", 0, "reason", "IT-DAY-ONBOARD"))), 200);
        long requestId = activation.at("/id").asLong();
        review(admin, requestId, "market-review", 0, key("activate-day-market"));
        review(finance, requestId, "finance-review", 1, key("activate-day-finance"));
        return id;
    }

    private Map<String, Object> changeBody(long institutionId, long baseVersion, String value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionId", institutionId);
        body.put("fieldCode", "settlementDay");
        body.put("newValue", value);
        body.put("baseVersion", baseVersion);
        body.put("reason", "IT-SETTLEMENT-DAY");
        return body;
    }

    private void review(AuthCookies actor, long id, String step, long version, String key) throws Exception {
        data(actor.withAuth(post("/api/v1/institution-change-requests/" + id + "/" + step))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version, "decision", "APPROVE", "comment", "IT-DAY-REVIEW"))), 200);
    }

    private JsonNode data(MockHttpServletRequestBuilder builder, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "IT-DAY-" + prefix + "-" + UUID.randomUUID();
    }
}
