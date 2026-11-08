package com.whale.crm.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogApiIT extends AbstractMysqlIT {

    @Test
    void p01_threeAndSixUseIndependentSkuSnapshots() throws Exception {
        AuthCookies admin = login("admin");
        JsonNode three = getJson("/api/v1/package-versions/301/snapshot", admin);
        JsonNode six = getJson("/api/v1/package-versions/302/snapshot", admin);

        assertThat(three.at("/choiceCount").asInt()).isEqualTo(3);
        assertThat(three.at("/projects/0/grantCount").asInt()).isEqualTo(3);
        assertThat(three.at("/skus/0/salePriceFen").asLong()).isEqualTo(181100L);
        assertThat(three.at("/skus/0/skuCode").asText()).isEqualTo("SKU-WG-001-03");
        assertThat(three.at("/entitlementIssuable").asBoolean()).isTrue();
        assertThat(six.at("/choiceCount").asInt()).isEqualTo(6);
        assertThat(six.at("/projects/0/grantCount").asInt()).isEqualTo(6);
        assertThat(six.at("/skus/0/salePriceFen").asLong()).isEqualTo(331100L);
        assertThat(six.at("/skus/0/skuCode").asText()).isEqualTo("SKU-WG-001-06");
        assertThat(three.at("/skus/0/id").asLong()).isNotEqualTo(six.at("/skus/0/id").asLong());

        JsonNode active = getJson("/api/v1/skus?packageId=201&status=ACTIVE&size=100", admin);
        assertThat(active.at("/total").asInt()).isGreaterThanOrEqualTo(2);
        Set<String> codes = new HashSet<>();
        active.at("/items").forEach(item -> codes.add(item.get("skuCode").asText()));
        assertThat(codes).contains("SKU-WG-001-03", "SKU-WG-001-06");
    }

    @Test
    void p02_newVersionDoesNotOverwriteActivatedSnapshot() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(patch("/api/v1/package-versions/301"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionMode\":\"USE_N\",\"choiceCount\":99,\"version\":0}"))
                .andExpect(status().isConflict());

        JsonNode created = data(postJson("/api/v1/packages/201/versions", admin,
                Map.of("selectionMode", "USE_N", "choiceCount", 8,
                        "projects", List.of(Map.of("projectId", 102, "grantCount", 8)))));
        long versionId = created.at("/id").asLong();
        assertThat(created.at("/status").asText()).isEqualTo("DRAFT");
        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + versionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        JsonNode sku = data(postJson("/api/v1/skus", admin, Map.of("skuCode", "SKU-IT-P02-08",
                "packageVersionId", versionId, "specName", "8次（新规则）", "salePriceFen", 222200,
                "institutionIds", List.of(1))));
        long skuId = sku.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salePriceFen").value(222200));

        JsonNode old = getJson("/api/v1/package-versions/301/snapshot", admin);
        assertThat(old.at("/choiceCount").asInt()).isEqualTo(3);
        assertThat(old.at("/projects/0/grantCount").asInt()).isEqualTo(3);
        assertThat(old.at("/skus/0/specName").asText()).isEqualTo("3次");
        assertThat(old.at("/skus/0/salePriceFen").asLong()).isEqualTo(181100L);
        assertThat(old.at("/status").asText()).isEqualTo("ACTIVE");
        JsonNode current = getJson("/api/v1/package-versions/" + versionId + "/snapshot", admin);
        assertThat(current.at("/choiceCount").asInt()).isEqualTo(8);
        assertThat(current.at("/projects/0/projectId").asLong()).isEqualTo(102L);
    }

    @Test
    void chooseNReportsQ01AndCannotActivateSku() throws Exception {
        AuthCookies admin = login("admin");
        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/311/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        JsonNode snapshot = getJson("/api/v1/package-versions/311/snapshot", admin);
        assertThat(snapshot.at("/rulesStatus").asText()).isEqualTo("PENDING_Q01");
        assertThat(snapshot.at("/allocationStatus").asText()).isEqualTo("PENDING_Q02");
        assertThat(snapshot.at("/entitlementIssuable").asBoolean()).isFalse();

        mockMvc.perform(admin.withAuth(post("/api/v1/skus/311/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.details[0].field").value("Q01"));
    }

    @Test
    void institutionUserSeesOnlyOwnInstitutionAndApplicableSkus() throws Exception {
        AuthCookies inst1 = login("inst1");
        JsonNode institutions = getJson("/api/v1/institutions", inst1);
        assertThat(institutions.at("/total").asInt()).isEqualTo(1);
        assertThat(institutions.at("/items/0/id").asLong()).isEqualTo(1L);

        JsonNode skus = getJson("/api/v1/skus?size=100", inst1);
        Set<String> codes = new HashSet<>();
        skus.at("/items").forEach(item -> codes.add(item.get("skuCode").asText()));
        assertThat(codes).contains("SKU-WG-001-03", "SKU-MS-001-S3")
                .doesNotContain("SKU-WG-001-06", "SKU-WG-001-10");
        mockMvc.perform(get("/api/v1/package-versions/302/snapshot").cookie(inst1.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/package-versions/301/snapshot").cookie(inst1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutions.length()").value(1))
                .andExpect(jsonPath("$.data.institutions[0].id").value(1));
        mockMvc.perform(inst1.withAuth(post("/api/v1/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectCode\":\"NO-PERMISSION\",\"projectName\":\"越权\",\"listPriceFen\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void catalogCrudDuplicateCodesPermissionsAndOptimisticLocks() throws Exception {
        AuthCookies admin = login("admin");
        Map<String, Object> project = Map.of("projectCode", "PRJ-IT-001", "projectName", "集成测试项目", "listPriceFen", 12345);
        JsonNode created = data(postJson("/api/v1/projects", admin, project));
        long projectId = created.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/projects"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(project)))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(patch("/api/v1/projects/" + projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"冲突\",\"listPriceFen\":1,\"status\":\"ACTIVE\",\"version\":99}"))
                .andExpect(status().isConflict());
        mockMvc.perform(admin.withAuth(patch("/api/v1/projects/" + projectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"集成测试项目（改）\",\"listPriceFen\":23456,\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.listPriceFen").value(23456))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(login("manager1").withAuth(post("/api/v1/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectCode\":\"PRJ-NO-WRITE\",\"projectName\":\"无权限\",\"listPriceFen\":0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void versionProjectsAndSkuInstitutionsAreEditableOnlyInDraft() throws Exception {
        AuthCookies admin = login("admin");
        JsonNode version = data(postJson("/api/v1/packages/201/versions", admin,
                Map.of("selectionMode", "USE_N", "choiceCount", 2,
                        "projects", List.of(Map.of("projectId", 101, "grantCount", 2)))));
        long versionId = version.at("/id").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/package-versions/" + versionId + "/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"projects\":[{\"projectId\":102,\"grantCount\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.projects[0].projectId").value(102));
        mockMvc.perform(admin.withAuth(put("/api/v1/package-versions/" + versionId + "/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"projects\":[{\"projectId\":103,\"grantCount\":2}]}"))
                .andExpect(status().isConflict());

        JsonNode sku = data(postJson("/api/v1/skus", admin, Map.of("skuCode", "SKU-IT-NO-INST",
                "packageVersionId", 301, "specName", "无机构测试", "salePriceFen", 100,
                "institutionIds", List.of())));
        long skuId = sku.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(patch("/api/v1/skus/" + skuId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specName\":\"无机构测试\",\"salePriceFen\":100,\"institutionIds\":[3],\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + skuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/skus/" + skuId + "/institutions").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(3));
        mockMvc.perform(admin.withAuth(post("/api/v1/skus"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuCode\":\"SKU-WG-001-03\",\"packageVersionId\":301,\"specName\":\"重复\",\"salePriceFen\":1,\"institutionIds\":[]}"))
                .andExpect(status().isConflict());
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                          AuthCookies... cookies) throws Exception {
        if (cookies.length > 0) {
            builder = builder.cookie(cookies[0].cookies());
        }
        String response = mockMvc.perform(builder)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }

    private JsonNode getJson(String url, AuthCookies actor) throws Exception {
        return data(get(url).cookie(actor.cookies()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
            String url, AuthCookies actor, Object body) throws Exception {
        return actor.withAuth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
