package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WecomMapperContractTest {
    private static final String NAMESPACE = WecomMapper.class.getName() + ".";
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        configuration = new Configuration();
        configuration.addMapper(WecomMapper.class);
    }

    @Test
    void selectContactsUsesDeferredJoinOnIsUnlinkedIndexForAllScope() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("scopeType", "ALL");
        params.put("teamIds", List.of());
        params.put("selfId", 2L);

        String sql = sql("selectContacts", params);

        assertThat(sql)
                .contains("FROM (SELECT c.id,c.is_unlinked FROM crm_wecom_contact c WHERE c.tenant_id=? AND 1=1 ORDER BY c.is_unlinked DESC,c.id DESC LIMIT 200) p INNER JOIN crm_wecom_contact c ON c.tenant_id=? AND c.id=p.id ORDER BY p.is_unlinked DESC,p.id DESC")
                .doesNotContain("c.customer_id IS NULL DESC");
    }

    @Test
    void selectContactsFiltersIsUnlinkedZeroForTeamAndSelfScopes() {
        Map<String, Object> teamParams = new HashMap<>();
        teamParams.put("tenantId", 1L);
        teamParams.put("scopeType", "TEAM");
        teamParams.put("teamIds", List.of(1L, 2L));
        teamParams.put("selfId", 2L);

        String teamSql = sql("selectContacts", teamParams);
        assertThat(teamSql).contains("c.is_unlinked=0 AND c.customer_id IS NOT NULL",
                "cu.team_id IN ( ? , ? )", "su.team_id IN ( ? , ? )");

        Map<String, Object> emptyTeamParams = new HashMap<>();
        emptyTeamParams.put("tenantId", 1L);
        emptyTeamParams.put("scopeType", "TEAM");
        emptyTeamParams.put("teamIds", List.of());
        emptyTeamParams.put("selfId", 2L);
        String emptyTeamSql = sql("selectContacts", emptyTeamParams);
        assertThat(emptyTeamSql).doesNotContain("IN ()");
    }

    @Test
    void insertGapCoalescesFromSeqAndMaintainsIdempotentUpsert() {
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 1L);
        params.put("corpId", "corp-1");
        params.put("archiveScope", "MOCK_ARCHIVE");
        params.put("gapType", "COLLECTOR_ERROR");
        params.put("fromSeq", null);
        params.put("toSeq", 10L);
        params.put("detail", "masked");
        params.put("now", Instant.parse("2026-11-25T10:00:00Z"));

        String sql = sql("insertGap", params);

        assertThat(sql).contains("COALESCE(?,0)", "ON DUPLICATE KEY UPDATE");
    }

    private String sql(String statement, Map<String, Object> params) {
        BoundSql boundSql = configuration.getMappedStatement(NAMESPACE + statement).getBoundSql(params);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }
}
