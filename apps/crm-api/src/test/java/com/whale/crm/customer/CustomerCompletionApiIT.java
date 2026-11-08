package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CustomerCompletionApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void ownerOptionsAreScopedAndAdminMustSelectEligibleOwner() throws Exception {
        AuthCookies admin = login("admin");
        String options = mockMvc.perform(get("/api/v1/customer-owner-options").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Set<Long> ids = new HashSet<>();
        objectMapper.readTree(options).at("/data").forEach(row -> ids.add(row.get("id").asLong()));
        assertThat(ids).contains(2L, 3L, 4L, 9L).doesNotContain(1L, 5L, 8L);

        String managerOptions = mockMvc.perform(get("/api/v1/customer-owner-options")
                        .cookie(login("manager1").cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(managerOptions).at("/data").size()).isEqualTo(1);
        assertThat(objectMapper.readTree(managerOptions).at("/data/0/id").asLong()).isEqualTo(2L);

        mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT收尾-未选管家\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT收尾-财务不是管家\",\"ownerStaffId\":5}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(login("manager1").withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT收尾-越组\",\"ownerStaffId\":3}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT收尾-指定管家\",\"ownerStaffId\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ownerStaffId").value(2))
                .andExpect(jsonPath("$.data.ownerName").value("王丽"));
    }

    @Test
    void legacyProfileHasTwentyThreeMappingsAndNeverExposesMobile() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "IT收尾-23字段", "13766665555", 2);
        String response = mockMvc.perform(get("/api/v1/customers/" + customerId + "/legacy-profile")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("13766665555");
        JsonNode fields = objectMapper.readTree(response).at("/data");
        assertThat(fields.size()).isEqualTo(23);
        assertThat(field(fields, "MOBILE").get("value").asText()).isEqualTo("137****5555");
        assertThat(field(fields, "ORDER_COUNT").get("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(field(fields, "ORDER_COUNT").get("value").asText()).isEqualTo("0");
        assertThat(field(fields, "ORDER_TOTAL_AMOUNT").get("availability").asText()).isEqualTo("AVAILABLE");
        assertThat(field(fields, "SUBMITTED_AT").get("availability").asText()).isEqualTo("PENDING_IMPORT");
        assertThat(field(fields, "MOBILE_DUPLICATED").get("value").asText()).isEqualTo("否");
        assertThat(field(fields, "MOBILE").get("originalTable").asText()).isEqualTo("客户信息表");
        assertThat(field(fields, "MOBILE").get("sourceFormula").asText()).contains("盲索引");

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/legacy-profile")
                        .cookie(login("manager2").cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void instantInputsRequireAnOffset() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(get("/api/v1/customers")
                        .param("followupDueBefore", "2026-10-24T09:00:00+08:00")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers")
                        .param("followupDueBefore", "2026-10-24T09:00:00")
                        .cookie(admin.cookies()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"IT收尾-时区\",\"ownerStaffId\":2,"
                                + "\"firstAddedAt\":\"2026-10-24T00:30:00+08:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.firstAddedAt").value("2026-10-23T16:30:00Z"));
    }

    @Test
    void mergeRequiresReviewAndPreservesPointsLedger() throws Exception {
        AuthCookies admin = login("admin");
        long primary = createCustomer(admin, "IT收尾-积分主", "13877776666", 2);
        long secondary = createCustomer(admin, "IT收尾-积分从", "13877776666", 2);
        adjustPoints(admin, primary, 5, "it-completion-points-primary");
        adjustPoints(admin, secondary, 7, "it-completion-points-secondary");

        String created = mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"primaryCustomerId\":" + primary + ",\"secondaryCustomerId\":" + secondary + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long caseId = objectMapper.readTree(created).at("/data/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/review"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/customer-merges/" + caseId + "/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/" + primary + "/points").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(12))
                .andExpect(jsonPath("$.data.ledger.length()").value(2));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT points_cached FROM crm_customer WHERE id=?", Integer.class, primary)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_points_ledger WHERE customer_id=?", Integer.class, secondary)).isZero();
    }

    @Test
    void transferRejectsNonStewardTarget() throws Exception {
        AuthCookies admin = login("admin");
        long customerId = createCustomer(admin, "IT收尾-转交资格", null, 2);
        mockMvc.perform(admin.withAuth(post("/api/v1/customers/" + customerId + "/ownership-transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOwnerId\":5,\"reason\":\"财务不能被当作管家\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/customers/" + customerId + "/ownership-transfers")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void corsAllowsBothLocalDevelopmentOrigins() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://127.0.0.1:5173"}) {
            mockMvc.perform(options("/api/v1/auth/login")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    private long createCustomer(AuthCookies actor, String nickname, String mobile, long ownerId) throws Exception {
        String mobileJson = mobile == null ? "" : ",\"mobile\":\"" + mobile + "\"";
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\",\"ownerStaffId\":" + ownerId + mobileJson + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private void adjustPoints(AuthCookies actor, long customerId, int delta, String key) throws Exception {
        mockMvc.perform(actor.withAuth(post("/api/v1/customers/" + customerId + "/points-adjustments"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":" + delta + ",\"reasonCode\":\"MANUAL\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode field(JsonNode fields, String key) {
        for (JsonNode field : fields) {
            if (key.equals(field.get("fieldKey").asText())) {
                return field;
            }
        }
        throw new AssertionError("缺少原字段映射 " + key);
    }
}
