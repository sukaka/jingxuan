package com.whale.crm.material;

import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialMapperContractTest {
    private static final String NAMESPACE = "com.whale.crm.material.infrastructure.mapper.MaterialMapper.";
    private Configuration configuration;

    @BeforeEach
    void parseMapper() throws Exception {
        configuration = new Configuration();
        String resource = "mapper/material/MaterialMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder builder = new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments());
            builder.parse();
        }
    }

    @Test
    void ordinaryReaderSqlOnlyIncludesApprovedRevisions() {
        String sql = sql("selectScopedRevisions", revisionParams(scope("ALL", true, false, false)));

        assertThat(sql).contains("r.status='APPROVED'", "m.offline_at IS NULL")
                .doesNotContain("PENDING")
                .doesNotContain("REJECTED")
                .doesNotContain("OR 1=1")
                .doesNotContain("r.created_by=?");
    }

    @Test
    void reviewerSqlAddsPendingAndRejectedButNotOtherDrafts() {
        String sql = sql("selectScopedRevisions", revisionParams(scope("ALL", false, false, true)));

        assertThat(sql).contains("r.status='APPROVED'", "PENDING", "REJECTED", "m.offline_at IS NULL OR 1=1")
                .doesNotContain("r.created_by=?");
    }

    @Test
    void institutionScopeIsInSqlAndExcludesGlobalNullRows() {
        MaterialScopeQuery scope = new MaterialScopeQuery("INSTITUTION", java.util.List.of(1L, 2L), 5,
                true, false, false);
        String sql = sql("selectScopedRevisions", revisionParams(scope));

        assertThat(sql).contains("m.institution_id IN", "r.status='APPROVED'")
                .doesNotContain("OR m.institution_id IS NULL");
    }

    @Test
    void searchFiltersRemainParameters() {
        String hostileKeyword = "%' OR 1=1 --";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("scope", scope("ALL", true, false, false));
        params.put("criteria", new MaterialSearchCriteria(hostileKeyword, "CASE", 1L, 2L, "310100",
                "APPROVED", false, 1, 20));
        params.put("offset", 0);
        params.put("size", 20);

        BoundSql bound = configuration.getMappedStatement(NAMESPACE + "search").getBoundSql(params);

        assertThat(normalize(bound.getSql())).doesNotContain(hostileKeyword).contains("v.status=?", "LIMIT ? OFFSET ?");
        assertThat(bound.getParameterMappings()).anyMatch(mapping -> mapping.getProperty().equals("criteria.keyword"));
    }

    @Test
    void draftUpdateHasTenantScopeStateAndOptimisticLockGuards() {
        Map<String, Object> params = new HashMap<>();
        params.put("entity", revision());
        params.put("scope", scope("ALL", true, true, false));

        String sql = sql("updateDraft", params);

        assertThat(sql).contains("r.tenant_id=?", "r.version=?", "r.status='DRAFT'", "EXISTS");
    }

    @Test
    void reviewUpdateEnforcesPendingAndDifferentReviewer() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("revisionId", 11L);
        params.put("materialId", 10L);
        params.put("expectedVersion", 2L);
        params.put("decision", "APPROVED");
        params.put("reviewerUserId", 8L);
        params.put("reviewNote", "合成审核");
        params.put("reviewedAt", Instant.parse("2026-10-27T08:00:00Z"));
        params.put("scope", scope("ALL", false, false, true));

        String sql = sql("reviewRevision", params);

        assertThat(sql).contains("r.tenant_id=?", "r.version=?", "r.status='PENDING'", "r.created_by<>?",
                "? IN ('APPROVED','REJECTED')", "EXISTS");
    }

    @Test
    void rejectionDoesNotRewriteCurrentApprovedPointer() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("materialId", 10L);
        params.put("expectedVersion", 3L);
        params.put("decision", "REJECTED");
        params.put("reviewerUserId", 8L);
        params.put("approvedRevisionId", null);
        params.put("scope", scope("ALL", false, false, true));

        String rejectedSql = sql("reviewMaterial", params);
        params.put("decision", "APPROVED");
        params.put("approvedRevisionId", 11L);
        String approvedSql = sql("reviewMaterial", params);

        assertThat(rejectedSql).doesNotContain("current_revision_id");
        assertThat(approvedSql).contains("m.current_revision_id=?");
    }

    @Test
    void offlineUpdateHasOptimisticLockAndDoesNotRewriteReviewFacts() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("materialId", 10L);
        params.put("expectedVersion", 3L);
        params.put("offlineBy", 8L);
        params.put("offlineReason", "合成下架原因");
        params.put("offlineAt", Instant.parse("2026-10-28T08:00:00Z"));
        params.put("scope", scope("ALL", true, true, false));

        String sql = sql("offlineMaterial", params);
        String assignments = sql.substring(sql.indexOf("SET"), sql.indexOf("WHERE"));

        assertThat(sql).contains("m.tenant_id=?", "m.version=?", "m.offline_at IS NULL",
                "m.current_revision_id IS NOT NULL", "approved.status='APPROVED'", "EXISTS");
        assertThat(assignments).contains("m.offline_at=?", "m.offline_by=?", "m.offline_reason=?",
                "m.version=m.version+1")
                .doesNotContain("review_status", "current_revision_id", "reviewer_id");
    }

    @Test
    void offlineStatusFilterUsesLifecycleInsteadOfRevisionStatus() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("scope", scope("ALL", true, true, false));
        params.put("criteria", new MaterialSearchCriteria(null, null, null, null, null,
                "OFFLINE", null, 1, 20));
        params.put("offset", 0);
        params.put("size", 20);

        String sql = sql("search", params);

        assertThat(sql).contains("v.material_offline_at IS NOT NULL").doesNotContain("v.status=?");
    }

    private Map<String, Object> revisionParams(MaterialScopeQuery scope) {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("materialId", 10L);
        params.put("scope", scope);
        return params;
    }

    private MaterialScopeQuery scope(String type, boolean read, boolean write, boolean review) {
        return new MaterialScopeQuery(type, java.util.List.of(), 5, read, write, review);
    }

    private MaterialRevision revision() {
        return new MaterialRevision(11L, 1L, 10L, 1, "合成素材", "合成摘要", "CASE", 1L, 2L,
                "310100", "[]", 99L, "合成修改", "DRAFT", null, null, null, 5L,
                Instant.parse("2026-10-27T08:00:00Z"), null, 2L);
    }

    private String sql(String statement, Map<String, Object> params) {
        return normalize(configuration.getMappedStatement(NAMESPACE + statement).getBoundSql(params).getSql());
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
