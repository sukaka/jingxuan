package com.whale.crm.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
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

class MaterialApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void emptySearchUsesRealMysqlWithoutRowAliasGrammarError() throws Exception {
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_material", Long.class);
        mockMvc.perform(get("/api/v1/materials")
                        .param("keyword", "mysql-empty-material-alias")
                        .cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void searchRejectsNonPositiveInstitutionAndProjectIds() throws Exception {
        mockMvc.perform(get("/api/v1/materials")
                        .param("institutionId", "0")
                        .cookie(login("admin").cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/materials")
                        .param("projectId", "-1")
                        .cookie(login("admin").cookies()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void latestRevisionIsSelectedWithinEachRolesVisibility() throws Exception {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) "
                        + "VALUES (1,?,?,?,100,?,'MATERIAL',NULL,'CLEAN',1)",
                "material/test/" + suffix, "proof.md", "text/markdown", "a".repeat(64));
        Long fileId = jdbcTemplate.queryForObject("SELECT id FROM crm_file_object WHERE object_key=?",
                Long.class, "material/test/" + suffix);
        String materialNo = "MT-TEST-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_material (tenant_id,material_no,title,category_code,institution_id,"
                        + "summary,tags_json,review_status,created_by) "
                        + "VALUES (1,?,'合成素材','GUIDE',NULL,'仅测试',JSON_ARRAY('synthetic'),'PENDING',1)", materialNo);
        Long materialId = jdbcTemplate.queryForObject("SELECT id FROM crm_material WHERE material_no=?",
                Long.class, materialNo);
        jdbcTemplate.update("INSERT INTO crm_material_revision (tenant_id,material_id,revision_no,title,category_code,"
                        + "summary,tags_json,file_id,status,reviewer_id,reviewed_at,created_by) "
                        + "VALUES (1,?,1,'合成素材','GUIDE','已审核版',JSON_ARRAY('synthetic'),?,'APPROVED',2,UTC_TIMESTAMP(3),1)",
                materialId, fileId);
        Long approvedRevision = jdbcTemplate.queryForObject("SELECT id FROM crm_material_revision "
                + "WHERE material_id=? AND revision_no=1", Long.class, materialId);
        jdbcTemplate.update("INSERT INTO crm_material_revision (tenant_id,material_id,revision_no,title,category_code,"
                        + "summary,tags_json,file_id,status,created_by) "
                        + "VALUES (1,?,2,'合成素材','GUIDE','待审版',JSON_ARRAY('synthetic'),?,'PENDING',1)",
                materialId, fileId);
        Long pendingRevision = jdbcTemplate.queryForObject("SELECT id FROM crm_material_revision "
                + "WHERE material_id=? AND revision_no=2", Long.class, materialId);
        jdbcTemplate.update("UPDATE crm_material SET current_revision_id=?, latest_revision_id=? WHERE id=?",
                approvedRevision, pendingRevision, materialId);
        jdbcTemplate.update("INSERT IGNORE INTO crm_material_tag (tenant_id,material_id,tag_code) VALUES (1,?,'synthetic')",
                materialId);

        mockMvc.perform(get("/api/v1/materials").param("keyword", materialNo).cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].currentRevisionId").value(approvedRevision))
                .andExpect(jsonPath("$.data.items[0].revisions[0].revisionNo").value(2))
                .andExpect(jsonPath("$.data.items[0].revisions[0].status").value("PENDING"));

        mockMvc.perform(get("/api/v1/materials").param("keyword", materialNo).cookie(login("manager1").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.items[0].currentRevisionId").value(approvedRevision))
                .andExpect(jsonPath("$.data.items[0].revisions.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].revisionNo").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].status").value("APPROVED"));

        mockMvc.perform(get("/api/v1/materials/{id}", materialId).cookie(login("admin").cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisions.length()").value(2))
                .andExpect(jsonPath("$.data.revisions[0].status").value("PENDING"));
    }

    @Test
    void offlineIsCsrfProtectedIdempotentAuditedAndHiddenFromOrdinaryManager() throws Exception {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) "
                        + "VALUES (1,?,?,?,100,?,'MATERIAL',NULL,'CLEAN',1)",
                "material/offline/" + suffix, "offline.md", "text/markdown", "b".repeat(64));
        Long fileId = jdbcTemplate.queryForObject("SELECT id FROM crm_file_object WHERE object_key=?",
                Long.class, "material/offline/" + suffix);
        String materialNo = "MT-OFFLINE-" + suffix;
        jdbcTemplate.update("INSERT INTO crm_material (tenant_id,material_no,title,category_code,institution_id,"
                        + "summary,tags_json,review_status,created_by) "
                        + "VALUES (1,?,'合成下架素材','GUIDE',NULL,'仅测试',JSON_ARRAY('synthetic'),'APPROVED',1)",
                materialNo);
        Long materialId = jdbcTemplate.queryForObject("SELECT id FROM crm_material WHERE material_no=?",
                Long.class, materialNo);
        jdbcTemplate.update("INSERT INTO crm_material_revision (tenant_id,material_id,revision_no,title,category_code,"
                        + "summary,tags_json,file_id,status,reviewer_id,reviewed_at,created_by) "
                        + "VALUES (1,?,1,'合成下架素材','GUIDE','已审核版',JSON_ARRAY('synthetic'),?,"
                        + "'APPROVED',2,UTC_TIMESTAMP(3),1)", materialId, fileId);
        Long revisionId = jdbcTemplate.queryForObject("SELECT id FROM crm_material_revision "
                + "WHERE material_id=? AND revision_no=1", Long.class, materialId);
        jdbcTemplate.update("UPDATE crm_material SET current_revision_id=?, latest_revision_id=? WHERE id=?",
                revisionId, revisionId, materialId);
        jdbcTemplate.update("INSERT IGNORE INTO crm_material_tag (tenant_id,material_id,tag_code) VALUES (1,?,'synthetic')",
                materialId);
        AuthCookies admin = login("admin");
        String path = "/api/v1/materials/" + materialId + "/offline";
        String key = "material-offline-" + suffix;
        String body = "{\"version\":0,\"reason\":\"授权到期，停止对客使用\"}";

        mockMvc.perform(post(path).cookie(admin.cookies()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(admin.withAuth(post(path)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.withAuth(post(path)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offlineAt").isNotEmpty())
                .andExpect(jsonPath("$.data.offlineReason").value("授权到期，停止对客使用"))
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.currentRevisionId").value(revisionId))
                .andExpect(jsonPath("$.data.revisions[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(post(path)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        mockMvc.perform(admin.withAuth(post(path)).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"reason\":\"不同请求体\"}"))
                .andExpect(status().isConflict());

        Integer auditCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log "
                + "WHERE tenant_id=1 AND action_code='MATERIAL_OFFLINE' AND target_type='MATERIAL' AND target_id=?",
                Integer.class, materialId.toString());
        assertThat(auditCount).isEqualTo(1);
        mockMvc.perform(get("/api/v1/materials/{id}", materialId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offlineAt").isNotEmpty())
                .andExpect(jsonPath("$.data.revisions[0].status").value("APPROVED"));

        AuthCookies manager = login("manager1");
        mockMvc.perform(get("/api/v1/materials").param("keyword", materialNo).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/materials/{id}", materialId).cookie(manager.cookies()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/materials/{id}/revisions/{revisionId}/download", materialId, revisionId)
                        .cookie(manager.cookies()))
                .andExpect(status().isNotFound());
    }

    @Test
    void lifecycleMaintainsLatestRevisionPointerAndTagsWithDualVisibilityIsolationAndTagAccuracy()
            throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long fileId = insertCleanMaterialFile("lifecycle-" + suffix);
        AuthCookies operation = login("operation1");
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");

        String tagInitial = "init-" + suffix;
        String tagUpdated = "upd-" + suffix;
        String tagApproved = "app-" + suffix;
        String tagMixedFallback = "Vip-Fallback-" + suffix;
        String tagMixedFallbackLower = "vip-fallback-" + suffix;
        String tagPending = "pend-" + suffix;
        String tagSuperVip = "supervip-" + suffix;

        // 1. Create material (r1 DRAFT): sets latest_revision_id = r1 & inserts crm_material_tag
        JsonNode created = data(operation.withAuth(post("/api/v1/materials"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "title", "生命周期素材-" + suffix,
                        "categoryCode", "GUIDE",
                        "summary", "初始摘要",
                        "tags", List.of(tagInitial, "中文标签-" + suffix),
                        "fileId", fileId,
                        "changeNote", "v1"))));
        long materialId = created.at("/id").asLong();
        long r1Id = created.at("/revisions/0/id").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT latest_revision_id FROM crm_material WHERE tenant_id=1 AND id=?", Long.class, materialId))
                .isEqualTo(r1Id);
        assertThat(tagCodes(materialId)).containsExactlyInAnyOrder(tagInitial, "中文标签-" + suffix);

        // 2. Patch draft (r1): replaces crm_material_tag (removes old tag, adds new tags + case-colliding Vip/vip)
        JsonNode patched = data(operation.withAuth(patch("/api/v1/materials/{id}", materialId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "revisionId", r1Id,
                        "version", 0,
                        "title", "生命周期素材-" + suffix,
                        "categoryCode", "GUIDE",
                        "summary", "更新摘要",
                        "tags", List.of(tagUpdated, tagApproved, "Vip-" + suffix, "vip-" + suffix, tagMixedFallback),
                        "fileId", fileId,
                        "changeNote", "v1-edit"))));
        assertThat(patched.at("/version").asLong()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT latest_revision_id FROM crm_material WHERE tenant_id=1 AND id=?", Long.class, materialId))
                .isEqualTo(r1Id);
        assertThat(tagCodes(materialId)).doesNotContain(tagInitial)
                .contains(tagUpdated, tagApproved, tagMixedFallback);

        // Removed tag no longer matches; case-insensitive tag lookup matches "vip-<suffix>" against "Vip-<suffix>"
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagInitial).cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/materials").param("keyword", "vip-" + suffix).cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagMixedFallbackLower).cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));

        // Submit & approve r1
        data(operation.withAuth(post("/api/v1/materials/{id}/revisions/{rid}/submit", materialId, r1Id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"));
        data(admin.withAuth(post("/api/v1/materials/{id}/revisions/{rid}/review", materialId, r1Id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"decision\":\"APPROVED\",\"reviewNote\":\"通过\"}"));

        // 3. Create r2 (DRAFT) and submit -> PENDING with different tag (tagPending & tagSuperVip)
        JsonNode r2Created = data(operation.withAuth(post("/api/v1/materials/{id}/revisions", materialId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "version", 3,
                        "title", "生命周期素材-" + suffix,
                        "categoryCode", "GUIDE",
                        "summary", "新版本待审",
                        "tags", List.of(tagPending, tagSuperVip),
                        "fileId", fileId,
                        "changeNote", "v2"))));
        long r2Id = r2Created.at("/revisions/0/id").asLong();
        assertThat(r2Id).isNotEqualTo(r1Id);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT latest_revision_id FROM crm_material WHERE tenant_id=1 AND id=?", Long.class, materialId))
                .isEqualTo(r2Id);
        assertThat(tagCodes(materialId)).containsExactlyInAnyOrder(tagPending, tagSuperVip);

        // While r2 is DRAFT, ordinary reader (manager1) still matches r1's mixed-case tag case-insensitively via r_cur
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagMixedFallbackLower).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].id").value(r1Id));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagPending).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        data(operation.withAuth(post("/api/v1/materials/{id}/revisions/{rid}/submit", materialId, r2Id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"));

        // 4. Dual-revision visibility & tag isolation:
        // Admin sees r2 (PENDING): matches tagPending, does NOT match tagApproved or r1's mixed-case fallback tag
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagPending).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].id").value(r2Id));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagApproved).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagMixedFallbackLower).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // Ordinary reader (manager1) sees r1 (APPROVED): MUST NOT leak unapproved r2's tagPending,
        // MUST match tagApproved and mixed-case "Vip-Fallback-*" (with lowercase query) via r_cur fallback
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagPending).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagApproved).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].id").value(r1Id));
        mockMvc.perform(get("/api/v1/materials").param("keyword", tagMixedFallbackLower).cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].revisions[0].id").value(r1Id));

        // 5. Substring & JSON punctuation / escape immunity
        mockMvc.perform(get("/api/v1/materials").param("keyword", "vip-" + suffix).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        for (String punctuationKeyword : List.of("[", "\"", ",", "\\u4e2d")) {
            mockMvc.perform(get("/api/v1/materials").param("keyword", punctuationKeyword)
                            .param("categoryCode", "GUIDE").cookie(admin.cookies()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));
        }
        // Blank keyword is ignored cleanly
        mockMvc.perform(get("/api/v1/materials").param("keyword", "   ")
                        .param("categoryCode", "GUIDE").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    private long insertCleanMaterialFile(String keySuffix) {
        String objectKey = "sealed/1/it-material-" + keySuffix;
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) "
                        + "VALUES (1,?,?,?,100,?,'MATERIAL',NULL,'CLEAN',1)",
                objectKey, "proof.md", "text/markdown", "c".repeat(64));
        return jdbcTemplate.queryForObject("SELECT id FROM crm_file_object WHERE object_key=?",
                Long.class, objectKey);
    }

    private List<String> tagCodes(long materialId) {
        return jdbcTemplate.queryForList("SELECT tag_code FROM crm_material_tag WHERE tenant_id=1 AND material_id=? "
                + "ORDER BY id", String.class, materialId);
    }

    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        String response = mockMvc.perform(request)
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data");
    }
}
