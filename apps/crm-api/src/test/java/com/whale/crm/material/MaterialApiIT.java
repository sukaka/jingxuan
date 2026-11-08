package com.whale.crm.material;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        jdbcTemplate.update("UPDATE crm_material SET current_revision_id=? WHERE id=?", approvedRevision, materialId);
        jdbcTemplate.update("INSERT INTO crm_material_revision (tenant_id,material_id,revision_no,title,category_code,"
                        + "summary,tags_json,file_id,status,created_by) "
                        + "VALUES (1,?,2,'合成素材','GUIDE','待审版',JSON_ARRAY('synthetic'),?,'PENDING',1)",
                materialId, fileId);

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
        jdbcTemplate.update("UPDATE crm_material SET current_revision_id=? WHERE id=?", revisionId, materialId);
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
}
