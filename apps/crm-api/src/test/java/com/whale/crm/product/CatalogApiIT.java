package com.whale.crm.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CatalogApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                        .content("{\"selectionMode\":\"FIXED\",\"choiceCount\":99,\"version\":0}"))
                .andExpect(status().isConflict());

        JsonNode created = data(postJson("/api/v1/packages/201/versions", admin,
                Map.of("selectionMode", "FIXED", "choiceCount", 8,
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
                Map.of("selectionMode", "FIXED", "choiceCount", 0,
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

    @Test
    void useNReportsQ01AndCannotActivateVersionSkuOrCreateOrder() throws Exception {
        AuthCookies admin = login("admin");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode pkg = data(postJson("/api/v1/packages", admin,
                Map.of("packageCode", "PKG-USEN-" + suffix, "packageName", "USE_N测试套餐")));
        long packageId = pkg.at("/id").asLong();

        JsonNode version = data(postJson("/api/v1/packages/" + packageId + "/versions", admin,
                Map.of("selectionMode", "USE_N", "choiceCount", 2,
                        "projects", List.of(Map.of("projectId", 101, "grantCount", 2)))));
        long draftVersionId = version.at("/id").asLong();
        assertThat(version.at("/rulesStatus").asText()).isEqualTo("PENDING_Q01");
        assertThat(version.at("/entitlementIssuable").asBoolean()).isFalse();

        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + draftVersionId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.details[0].field").value("Q01"));

        JsonNode draftSku = data(postJson("/api/v1/skus", admin, Map.of("skuCode", "SKU-USEN-" + suffix,
                "packageVersionId", draftVersionId, "specName", "USE_N模板", "salePriceFen", 1000,
                "institutionIds", List.of(1))));
        long draftSkuId = draftSku.at("/id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/skus/" + draftSkuId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.details[0].field").value("Q01"));

        jdbcTemplate.update("INSERT INTO crm_package_version (tenant_id,package_id,version_no,selection_mode,choice_count,status) "
                + "VALUES (1,?,2,'USE_N',2,'ACTIVE')", packageId);
        Long activeUseNVersionId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_package_version WHERE tenant_id=1 AND package_id=? AND version_no=2",
                Long.class, packageId);
        jdbcTemplate.update("INSERT INTO crm_package_project (tenant_id,package_version_id,project_id,grant_count,selection_weight,allocation_price_fen,option_group,reference_price_fen) "
                + "VALUES (1,?,101,2,1,1000,'MAIN',39900)", activeUseNVersionId);
        String activeSkuCode = "SKU-USEN-ACT-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_sku (tenant_id,sku_code,package_version_id,spec_name,sale_price_fen,status,on_sale_at) "
                + "VALUES (1,?,?,'USE_N历史生效',1000,'ACTIVE',NOW(3))", activeSkuCode, activeUseNVersionId);
        Long activeSkuId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_sku WHERE tenant_id=1 AND sku_code=?", Long.class, activeSkuCode);
        jdbcTemplate.update("INSERT INTO crm_sku_institution (tenant_id,sku_id,institution_id,status) VALUES (1,?,1,'ACTIVE')",
                activeSkuId);

        JsonNode customer = data(postJson("/api/v1/customers", admin,
                Map.of("nickname", "IT-USEN-" + suffix, "ownerStaffId", 2)));
        long customerId = customer.at("/id").asLong();
        Map<String, Object> orderBody = Map.of("customerId", customerId, "receiverType", "PLATFORM",
                "orderTypeCode", "REGULAR", "campaignId", 1, "campaignBatchId", 1,
                "payableFen", 1000, "validTo", "2027-12-31",
                "items", List.of(Map.of("skuId", activeSkuId, "quantity", 1)));
        mockMvc.perform(admin.withAuth(post("/api/v1/orders"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(orderBody)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"))
                .andExpect(jsonPath("$.details[0].field").value("Q01"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order WHERE tenant_id=1 AND customer_id=?",
                Integer.class, customerId)).isZero();

        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/youzan/sku-mappings"))
                        .header("Idempotency-Key", "IT-USEN-YZ-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("shopId", "YZ-SHOP-DEMO", "externalSkuId", "YZ-USEN-" + suffix,
                                "crmSkuId", activeUseNVersionId))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PENDING_BUSINESS_RULE"));
    }

    @Test
    void institutionUserFiltersPackagesAndVersions() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies inst1 = login("inst1");
        AuthCookies manager1 = login("manager1");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String hiddenCode = "PKG-INST2-" + suffix;
        JsonNode hiddenPkg = data(postJson("/api/v1/packages", admin,
                Map.of("packageCode", hiddenCode, "packageName", "仅机构2可见套餐")));
        long hiddenPkgId = hiddenPkg.at("/id").asLong();
        JsonNode hiddenVersion = data(postJson("/api/v1/packages/" + hiddenPkgId + "/versions", admin,
                Map.of("selectionMode", "FIXED", "choiceCount", 0,
                        "projects", List.of(Map.of("projectId", 101, "grantCount", 1)))));
        long hiddenVersionId = hiddenVersion.at("/id").asLong();
        postJson("/api/v1/skus", admin, Map.of("skuCode", "SKU-INST2-" + suffix,
                "packageVersionId", hiddenVersionId, "specName", "机构2规格", "salePriceFen", 1000,
                "institutionIds", List.of(2)));
        mockMvc.perform(admin.withAuth(post("/api/v1/skus"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("skuCode", "SKU-INST2-ONLY-" + suffix,
                                "packageVersionId", hiddenVersionId, "specName", "机构2专属", "salePriceFen", 1000,
                                "institutionIds", List.of(2)))))
                .andExpect(status().isCreated());

        JsonNode instPackages = getJson("/api/v1/packages?size=100", inst1);
        Set<String> instCodes = new HashSet<>();
        instPackages.at("/items").forEach(item -> instCodes.add(item.get("packageCode").asText()));
        assertThat(instCodes).contains("PKG-WG", "PKG-SY").doesNotContain(hiddenCode);

        JsonNode pkg201Versions = getJson("/api/v1/packages/201/versions", inst1);
        Set<Long> versionIds = new HashSet<>();
        pkg201Versions.forEach(item -> versionIds.add(item.get("id").asLong()));
        assertThat(versionIds).contains(301L).doesNotContain(302L, 303L);

        mockMvc.perform(get("/api/v1/packages/" + hiddenPkgId + "/versions").cookie(inst1.cookies()))
                .andExpect(status().isNotFound());

        JsonNode adminPackages = getJson("/api/v1/packages?size=100", admin);
        Set<String> adminCodes = new HashSet<>();
        adminPackages.at("/items").forEach(item -> adminCodes.add(item.get("packageCode").asText()));
        assertThat(adminCodes).contains("PKG-WG", "PKG-SY", hiddenCode);
        assertThat(getJson("/api/v1/packages/" + hiddenPkgId + "/versions", manager1).size()).isEqualTo(1);
    }

    @Test
    void fixedVersionRejectsDuplicateProjectAcrossOptionGroupsAndUpdateCleansGroupsAndChecksInactiveProjects()
            throws Exception {
        AuthCookies admin = login("admin");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode pkg = data(postJson("/api/v1/packages", admin,
                Map.of("packageCode", "PKG-MBE08-" + suffix, "packageName", "MBE08测试套餐")));
        long packageId = pkg.at("/id").asLong();

        mockMvc.perform(postJson("/api/v1/packages/" + packageId + "/versions", admin,
                        Map.of("selectionMode", "FIXED", "choiceCount", 0,
                                "projects", List.of(
                                        Map.of("projectId", 101, "grantCount", 1, "optionGroup", "A"),
                                        Map.of("projectId", 101, "grantCount", 2, "optionGroup", "B")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode fixedDraft = data(postJson("/api/v1/packages/" + packageId + "/versions", admin,
                Map.of("selectionMode", "FIXED", "choiceCount", 0,
                        "projects", List.of(Map.of("projectId", 101, "grantCount", 1)))));
        long fixedDraftId = fixedDraft.at("/id").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/package-versions/" + fixedDraftId + "/projects"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("version", 0, "projects", List.of(
                                Map.of("projectId", 101, "grantCount", 1, "optionGroup", "A"),
                                Map.of("projectId", 101, "grantCount", 2, "optionGroup", "B"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode chooseNDraft = data(postJson("/api/v1/packages/" + packageId + "/versions", admin,
                Map.of("selectionMode", "CHOOSE_N", "choiceCount", 1,
                        "groups", List.of(Map.of("groupCode", "A", "groupName", "A组",
                                "minSelections", 1, "maxSelections", 1, "sortOrder", 1)),
                        "projects", List.of(
                                Map.of("projectId", 101, "grantCount", 1, "optionGroup", "A", "referencePriceFen", 39900),
                                Map.of("projectId", 102, "grantCount", 1, "optionGroup", "A", "referencePriceFen", 21900)))));
        long chooseNId = chooseNDraft.at("/id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_package_selection_group WHERE tenant_id=1 AND package_version_id=?",
                Integer.class, chooseNId)).isEqualTo(1);

        mockMvc.perform(admin.withAuth(patch("/api/v1/package-versions/" + chooseNId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionMode\":\"FIXED\",\"choiceCount\":0,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectionMode").value("FIXED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_package_selection_group WHERE tenant_id=1 AND package_version_id=?",
                Integer.class, chooseNId)).isZero();

        mockMvc.perform(admin.withAuth(post("/api/v1/package-versions/" + chooseNId + "/activate"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        JsonNode tempProject = data(postJson("/api/v1/projects", admin,
                Map.of("projectCode", "PRJ-INACT-" + suffix, "projectName", "停用检查项目", "listPriceFen", 1000)));
        long tempProjectId = tempProject.at("/id").asLong();
        JsonNode inactiveProjectVersion = data(postJson("/api/v1/packages/" + packageId + "/versions", admin,
                Map.of("selectionMode", "FIXED", "choiceCount", 0,
                        "projects", List.of(Map.of("projectId", tempProjectId, "grantCount", 1)))));
        long inactiveVersionId = inactiveProjectVersion.at("/id").asLong();
        mockMvc.perform(admin.withAuth(patch("/api/v1/projects/" + tempProjectId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectName\":\"停用检查项目\",\"listPriceFen\":1000,\"status\":\"INACTIVE\",\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(patch("/api/v1/package-versions/" + inactiveVersionId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectionMode\":\"FIXED\",\"choiceCount\":0,\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
