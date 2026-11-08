package com.whale.crm.institution;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstitutionMerchantResourceApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void createFileFixtures() {
        for (long fileId : List.of(9901L, 9902L, 9903L)) {
            jdbcTemplate.update("INSERT INTO crm_file_object (id,tenant_id,object_key,mime_type,size_bytes,sha256,"
                            + "classification,institution_id,scan_status,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    fileId, 1, "sealed/it-merchant-" + fileId + "-" + UUID.randomUUID(), "image/png", 1,
                    "0000000000000000000000000000000000000000000000000000000000000000",
                    "MERCHANT_RESOURCE", fileId == 9903L ? 2 : 1, fileId == 9901L ? "PENDING" : "CLEAN", 6);
        }
    }

    @Test
    void businessMaintainsRealResourcesAndInstitutionScopeIsEnforced() throws Exception {
        AuthCookies business = login("business1");
        AuthCookies operator = login("operator1");
        AuthCookies otherOperator = login("operator2");
        String createKey = key("doctor-create");
        Map<String, Object> doctorBody = Map.of("resourceType", "DOCTOR", "title", "测试医生",
                "subtitle", "主治医师", "description", "测试项目服务说明");
        JsonNode doctor = data(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                .header("Idempotency-Key", createKey).contentType(MediaType.APPLICATION_JSON).content(json(doctorBody)));
        long doctorId = doctor.at("/id").asLong();
        assertThat(doctor.at("/resourceType").asText()).isEqualTo("DOCTOR");
        assertThat(doctor.at("/status").asText()).isEqualTo("PUBLISHED");
        assertThat(doctor.at("/version").asLong()).isZero();
        assertThat(data(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                .header("Idempotency-Key", createKey).contentType(MediaType.APPLICATION_JSON)
                .content(json(doctorBody))).at("/id").asLong()).isEqualTo(doctorId);
        assertThat(countResources(doctorId)).isEqualTo(1);

        create(business, "EQUIPMENT", "测试仪器", "设备型号", "测试设备说明", null);
        create(business, "CASE", "测试案例", "测试项目", "经授权的案例说明", null);

        String updateKey = key("doctor-update");
        Map<String, Object> updateBody = Map.of("version", 0, "title", "测试医生（已更新）",
                "subtitle", "主任医师", "description", "更新后的真实资料说明", "status", "PUBLISHED");
        JsonNode updated = data(business.withAuth(patch("/api/v1/institution-merchant-resources/" + doctorId))
                .header("Idempotency-Key", updateKey).contentType(MediaType.APPLICATION_JSON).content(json(updateBody)));
        assertThat(updated.at("/title").asText()).isEqualTo("测试医生（已更新）");
        assertThat(updated.at("/version").asLong()).isEqualTo(1);
        assertThat(data(business.withAuth(patch("/api/v1/institution-merchant-resources/" + doctorId))
                .header("Idempotency-Key", updateKey).contentType(MediaType.APPLICATION_JSON)
                .content(json(updateBody))).at("/version").asLong()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/institution-merchant-resources?institutionId=1&resourceType=DOCTOR")
                        .cookie(operator.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("测试医生（已更新）"));
        mockMvc.perform(get("/api/v1/institution-merchant-resources?institutionId=1")
                        .cookie(otherOperator.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(otherOperator.withAuth(patch("/api/v1/institution-merchant-resources/" + doctorId))
                        .header("Idempotency-Key", key("operator-write"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(updateBody)))
                .andExpect(status().isForbidden());
        mockMvc.perform(business.withAuth(patch("/api/v1/institution-merchant-resources/" + doctorId))
                        .header("Idempotency-Key", key("stale-update"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(updateBody)))
                .andExpect(status().isConflict());

        JsonNode summaries = data(get("/api/v1/institution-merchant-resources/summary").cookie(operator.cookies()));
        JsonNode summary = findInstitution(summaries, 1);
        assertThat(summary.at("/doctorCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/equipmentCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/caseCount").asLong()).isEqualTo(1);
        assertThat(findInstitution(data(get("/api/v1/institution-merchant-resources/summary")
                .cookie(otherOperator.cookies())), 1).isMissingNode()).isTrue();

        Map<String, Object> offlineBody = Map.of("version", 1, "title", "测试医生（已更新）",
                "subtitle", "主任医师", "description", "更新后的真实资料说明", "status", "OFFLINE");
        mockMvc.perform(business.withAuth(patch("/api/v1/institution-merchant-resources/" + doctorId))
                        .header("Idempotency-Key", key("doctor-offline"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(offlineBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OFFLINE"));
        mockMvc.perform(get("/api/v1/institution-merchant-resources?institutionId=1&resourceType=DOCTOR")
                        .cookie(operator.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/institution-merchant-resources?institutionId=1&resourceType=DOCTOR&status=ALL")
                        .cookie(operator.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value("OFFLINE"));
        summary = findInstitution(data(get("/api/v1/institution-merchant-resources/summary").cookie(operator.cookies())), 1);
        assertThat(summary.at("/doctorCount").asLong()).isZero();
        assertThat(summary.at("/equipmentCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/caseCount").asLong()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='INSTITUTION_MERCHANT_RESOURCE_UPDATE' AND target_id=?", Integer.class,
                Long.toString(doctorId))).isEqualTo(2);
    }

    @Test
    void attachmentsMustBeCleanAndBelongToTheSameInstitution() throws Exception {
        AuthCookies business = login("business1");
        mockMvc.perform(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                        .header("Idempotency-Key", key("pending-file"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resourceBody("CASE", "未扫描案例", 9901L))))
                .andExpect(status().isConflict());
        mockMvc.perform(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                        .header("Idempotency-Key", key("other-file"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(resourceBody("CASE", "跨机构附件案例", 9903L))))
                .andExpect(status().isForbidden());
        JsonNode created = data(business.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                .header("Idempotency-Key", key("clean-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(resourceBody("CASE", "已扫描案例", 9902L))));
        assertThat(created.at("/fileId").asLong()).isEqualTo(9902L);
        assertThat(countResources(created.at("/id").asLong())).isEqualTo(1);
    }

    private JsonNode create(AuthCookies actor, String type, String title, String subtitle,
                            String description, Long fileId) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("resourceType", type);
        body.put("title", title);
        body.put("subtitle", subtitle);
        body.put("description", description);
        body.put("fileId", fileId);
        return data(actor.withAuth(post("/api/v1/institution-profiles/1/merchant-resources"))
                .header("Idempotency-Key", key("create-" + type)).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private Map<String, Object> resourceBody(String type, String title, long fileId) {
        return Map.of("resourceType", type, "title", title, "description", "合成测试资料", "fileId", fileId);
    }

    private int countResources(long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_institution_merchant_resource WHERE tenant_id=1 AND id=?",
                Integer.class, id);
    }

    private JsonNode findInstitution(JsonNode items, long institutionId) {
        for (JsonNode item : items) {
            if (item.at("/institutionId").asLong() == institutionId) {
                return item;
            }
        }
        return objectMapper.createObjectNode().missingNode();
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private String key(String prefix) {
        return "IT-MERCHANT-" + prefix + "-" + UUID.randomUUID();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
