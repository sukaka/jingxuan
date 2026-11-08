package com.whale.crm.file;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P2OperationsGovernanceSchemaIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");
    private static DataSource dataSource;

    static {
        MYSQL.start();
    }

    private Connection connection;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrate() {
        MysqlDataSource mysql = new MysqlDataSource();
        mysql.setURL(MYSQL.getJdbcUrl());
        mysql.setUser("root");
        mysql.setPassword(MYSQL.getPassword());
        dataSource = mysql;
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/dev")
                .baselineOnMigrate(true).load().migrate();
    }

    @BeforeEach
    void beginTransaction() throws SQLException {
        connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void v17CreatesGovernanceTablesAndOnlyAddsIncrementalColumns() {
        List<String> tables = jdbcTemplate.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name IN "
                + "('crm_material','crm_material_revision','crm_export_job','crm_export_access_log',"
                + "'crm_risk_case','crm_risk_case_history','crm_daily_analytics','crm_performance_snapshot')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder("crm_material", "crm_material_revision", "crm_export_job",
                "crm_export_access_log", "crm_risk_case", "crm_risk_case_history", "crm_daily_analytics",
                "crm_performance_snapshot");

        List<String> orderColumns = jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='crm_order'", String.class);
        assertThat(orderColumns).contains("campaign_code_snapshot", "campaign_name_snapshot", "batch_code_snapshot",
                "batch_name_snapshot", "channel_id", "business_owner_at_sale_id");
        String batchStatusDefault = jdbcTemplate.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='crm_campaign_batch' AND column_name='status'",
                String.class);
        assertThat(batchStatusDefault).isEqualTo("ACTIVE");
    }

    @Test
    void tenantBusinessUniqueKeysAndIntegerAnalyticsTypesMatchContract() {
        assertUniqueKey("crm_material", "tenant_id,material_no");
        assertUniqueKey("crm_material_revision", "tenant_id,material_id,revision_no");
        assertUniqueKey("crm_export_job", "tenant_id,requested_by,idempotency_key");
        assertUniqueKey("crm_risk_case", "tenant_id,risk_case_no");
        assertUniqueKey("crm_risk_case", "tenant_id,source_type,source_ref,risk_type");
        assertUniqueKey("crm_daily_analytics",
                "tenant_id,stat_date,dimension_type,dimension_ref,metric_code,metric_version");
        assertUniqueKey("crm_performance_snapshot", "tenant_id,period_key,staff_user_id,performance_role");

        assertThat(columnType("crm_daily_analytics", "metric_value")).isEqualTo("bigint");
        for (String column : List.of("gross_fen", "refund_fen", "net_fen", "target_fen")) {
            assertThat(columnType("crm_performance_snapshot", column)).isEqualTo("bigint");
        }
        assertThat(columnNullable("crm_performance_snapshot", "target_fen")).isEqualTo("YES");
        Long nonIntegerMetrics = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name IN ('crm_daily_analytics','crm_performance_snapshot') "
                + "AND data_type IN ('float','double','decimal')", Long.class);
        assertThat(nonIntegerMetrics).isZero();
    }

    @Test
    void developmentPermissionsDoNotWidenOrdinaryManagerExportOrLeadScope() {
        assertThat(rolePermissions("MANAGER")).contains("material:read", "report:read")
                .doesNotContain("export:create");
        assertThat(rolePermissions("MANAGER_LEAD")).contains("export:create", "export:read");

        List<String> leadScopes = jdbcTemplate.queryForList("SELECT scope_type FROM sys_data_scope ds "
                + "JOIN sys_user u ON u.tenant_id=ds.tenant_id AND u.id=ds.user_id "
                + "WHERE ds.tenant_id=1 AND u.username='lead1' AND ds.resource='customer'", String.class);
        assertThat(leadScopes).contains("TEAM").doesNotContain("ALL");
        List<String> managerScopes = jdbcTemplate.queryForList("SELECT scope_type FROM sys_data_scope ds "
                + "JOIN sys_user u ON u.tenant_id=ds.tenant_id AND u.id=ds.user_id "
                + "WHERE ds.tenant_id=1 AND u.username='manager1' AND ds.resource='customer'", String.class);
        assertThat(managerScopes).containsExactly("SELF");
    }

    @Test
    void finalMaterialRevisionCannotBeOverwrittenOrDeleted() {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_file_object (tenant_id,object_key,original_name,mime_type,size_bytes,"
                + "sha256,classification,institution_id,scan_status,uploaded_by) VALUES (1,?,?,?,1,?,'MATERIAL',NULL,'CLEAN',1)",
                "material/1/" + suffix, "synthetic.pdf", "application/pdf", "a".repeat(64));
        Long fileId = id("crm_file_object", "object_key", "material/1/" + suffix);
        jdbcTemplate.update("INSERT INTO crm_material (tenant_id,material_no,title,category_code,review_status,created_by) "
                + "VALUES (1,?,'合成素材','CASE','DRAFT',1)", "IT-MAT-" + suffix);
        Long materialId = jdbcTemplate.queryForObject("SELECT id FROM crm_material WHERE tenant_id=1 AND material_no=?",
                Long.class, "IT-MAT-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_material_revision (tenant_id,material_id,revision_no,title,category_code,"
                + "file_id,status,created_by) VALUES (1,?,1,'合成版本','CASE',?,'PENDING',1)", materialId, fileId);
        Long revisionId = jdbcTemplate.queryForObject("SELECT id FROM crm_material_revision "
                + "WHERE tenant_id=1 AND material_id=? AND revision_no=1", Long.class, materialId);
        jdbcTemplate.update("UPDATE crm_material_revision SET status='APPROVED',reviewer_id=1,reviewed_at=UTC_TIMESTAMP(3),"
                + "version=version+1 WHERE tenant_id=1 AND id=?", revisionId);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_material_revision SET title='禁止覆盖' "
                + "WHERE tenant_id=1 AND id=?", revisionId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_material_revision "
                + "WHERE tenant_id=1 AND id=?", revisionId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void exportAccessLogIsAppendOnly() {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_export_job (tenant_id,job_no,requested_by,export_type,status,filters_json,"
                + "columns_json,scope_json,reason,idempotency_key,request_hash) VALUES (1,?,1,'CUSTOMER','PENDING',"
                + "'{}','[]','{}','合成 schema 测试',?,?)", "IT-EXP-" + suffix, suffix, "b".repeat(64));
        Long jobId = jdbcTemplate.queryForObject("SELECT id FROM crm_export_job WHERE tenant_id=1 AND job_no=?",
                Long.class, "IT-EXP-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_export_access_log (tenant_id,job_id,actor_user_id,action,result,reason) "
                + "VALUES (1,?,1,'REQUEST','ALLOWED','合成 schema 测试')", jobId);
        Long logId = jdbcTemplate.queryForObject("SELECT id FROM crm_export_access_log WHERE tenant_id=1 AND job_id=?",
                Long.class, jobId);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_export_access_log SET result='DENIED' "
                + "WHERE tenant_id=1 AND id=?", logId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_export_access_log "
                + "WHERE tenant_id=1 AND id=?", logId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void riskHistoryIsAppendOnly() {
        String suffix = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO crm_risk_case (tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,"
                + "status,evidence) VALUES (1,?,'SECURITY','HIGH','SYNTHETIC',?,'OPEN','合成 schema 测试')",
                "IT-RISK-" + suffix, suffix);
        Long riskId = jdbcTemplate.queryForObject("SELECT id FROM crm_risk_case WHERE tenant_id=1 AND risk_case_no=?",
                Long.class, "IT-RISK-" + suffix);
        jdbcTemplate.update("INSERT INTO crm_risk_case_history (tenant_id,risk_case_id,action,from_status,to_status,"
                + "actor_user_id,note) VALUES (1,?,'CREATE',NULL,'OPEN',1,'合成 schema 测试')", riskId);
        Long historyId = jdbcTemplate.queryForObject("SELECT id FROM crm_risk_case_history "
                + "WHERE tenant_id=1 AND risk_case_id=?", Long.class, riskId);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_risk_case_history SET note='禁止覆盖' "
                + "WHERE tenant_id=1 AND id=?", historyId)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM crm_risk_case_history "
                + "WHERE tenant_id=1 AND id=?", historyId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void orderLifecycleCanAdvanceButSaleSnapshotsCannotBeOverwritten() {
        String orderNo = "IT-ORDER-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,campaign_id,campaign_batch_id,status,receiver_type,list_amount_fen,"
                + "discount_amount_fen,payable_fen,paid_fen,refunded_fen,ordered_at,campaign_code_snapshot,"
                + "campaign_name_snapshot,batch_code_snapshot,batch_name_snapshot,channel_id,business_owner_at_sale_id) "
                + "VALUES (1,?,1001,2,2,'CRM','REGULAR',1,1,'DRAFT','PLATFORM',1,0,1,0,0,UTC_TIMESTAMP(3),"
                + "'DAILY','日常销售','DEFAULT','默认批次',1,6)", orderNo);
        Long orderId = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, orderNo);
        jdbcTemplate.update("UPDATE crm_order SET status='PENDING_PAYMENT',version=version+1 "
                + "WHERE tenant_id=1 AND id=?", orderId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_order WHERE tenant_id=1 AND id=?",
                String.class, orderId)).isEqualTo("PENDING_PAYMENT");

        for (String column : List.of("campaign_code_snapshot", "campaign_name_snapshot", "batch_code_snapshot",
                "batch_name_snapshot")) {
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_order SET " + column + "='禁止覆盖' "
                    + "WHERE tenant_id=1 AND id=?", orderId)).isInstanceOf(DataAccessException.class);
        }
        for (String column : List.of("channel_id", "business_owner_at_sale_id")) {
            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE crm_order SET " + column + "=NULL "
                    + "WHERE tenant_id=1 AND id=?", orderId)).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void rollbackRemovesOnlyV17Artifacts() throws Exception {
        String schema = "p2_rollback_verify";
        try (Connection admin = dataSource.getConnection(); java.sql.Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource rollbackDataSource = new MysqlDataSource();
        rollbackDataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + schema + "?"));
        rollbackDataSource.setUser("root");
        rollbackDataSource.setPassword(MYSQL.getPassword());
        try {
            Flyway.configure().dataSource(rollbackDataSource).schemas(schema)
                    .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
            for (String script : List.of(
                    "V21__p2_kb_2_knowledge_publication_rollback.sql",
                    "V20__p2_kb_1_dify_knowledge_config_rollback.sql",
                    "V19__task_action_workflow_rollback.sql",
                    "V18__p2_order_type_snapshot_rollback.sql",
                    "V17__p2_operations_governance_rollback.sql")) {
                try (Connection rollbackConnection = rollbackDataSource.getConnection()) {
                    org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(rollbackConnection,
                            new org.springframework.core.io.ClassPathResource("db/rollback/" + script));
                }
            }
            JdbcTemplate verification = new JdbcTemplate(rollbackDataSource);
            Long newTables = verification.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=? AND table_name IN "
                    + "('crm_material','crm_material_revision','crm_export_job','crm_export_access_log',"
                    + "'crm_risk_case','crm_risk_case_history','crm_daily_analytics','crm_performance_snapshot')",
                    Long.class, schema);
            assertThat(newTables).isZero();
            Long newColumns = verification.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND ((table_name='crm_order' AND column_name IN "
                    + "('campaign_code_snapshot','campaign_name_snapshot','batch_code_snapshot','batch_name_snapshot',"
                    + "'channel_id','business_owner_at_sale_id')) OR (table_name='crm_campaign_batch' "
                    + "AND column_name='status'))", Long.class, schema);
            assertThat(newColumns).isZero();
            Long triggers = verification.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                    + "WHERE trigger_schema=? AND trigger_name IN "
                    + "('trg_material_revision_no_update','trg_material_revision_no_delete',"
                    + "'trg_export_access_no_update','trg_export_access_no_delete',"
                    + "'trg_risk_history_no_update','trg_risk_history_no_delete',"
                    + "'trg_order_sale_snapshot_no_update')", Long.class, schema);
            assertThat(triggers).isZero();
            Long baseTables = verification.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=? AND table_name IN ('crm_order','crm_campaign_batch','crm_file_object')",
                    Long.class, schema);
            assertThat(baseTables).isEqualTo(3);
        } finally {
            try (Connection admin = dataSource.getConnection(); java.sql.Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private void assertUniqueKey(String table, String columns) {
        Long matches = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (SELECT index_name "
                + "FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND non_unique=0 "
                + "GROUP BY index_name HAVING GROUP_CONCAT(column_name ORDER BY seq_in_index)=?) unique_keys",
                Long.class, table, columns);
        assertThat(matches).as("unique key %s on %s", columns, table).isEqualTo(1);
    }

    private String columnType(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, column);
    }

    private String columnNullable(String table, String column) {
        return jdbcTemplate.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?", String.class, table, column);
    }

    private List<String> rolePermissions(String role) {
        return jdbcTemplate.queryForList("SELECT p.permission_code FROM sys_role_permission rp "
                + "JOIN sys_role r ON r.tenant_id=rp.tenant_id AND r.id=rp.role_id "
                + "JOIN sys_permission p ON p.tenant_id=rp.tenant_id AND p.id=rp.permission_id "
                + "WHERE rp.tenant_id=1 AND r.role_code=?", String.class, role);
    }

    private Long id(String table, String keyColumn, String key) {
        return jdbcTemplate.queryForObject("SELECT id FROM " + table + " WHERE tenant_id=1 AND " + keyColumn + "=?",
                Long.class, key);
    }
}
