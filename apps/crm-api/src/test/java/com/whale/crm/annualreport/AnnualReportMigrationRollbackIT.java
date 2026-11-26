package com.whale.crm.annualreport;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnnualReportMigrationRollbackIT {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1")
            .withTmpFs(Map.of("/var/lib/mysql", "rw,mode=1777"))
            .withEnv("MYSQL_INITDB_SKIP_TZINFO", "1")
            .withStartupTimeoutSeconds(600);

    static {
        MYSQL.start();
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @Test
    void emptyHistoryAllowsCleanRollbackWithoutTouchingOtherModules() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            assertThat(count(connection, schema, "TABLES", "crm_annual_report%")).isEqualTo(3);
            assertThat(count(connection, schema, "TRIGGERS", "trg_annual_%")).isEqualTo(3);

            ExecResult result = runMysql(schema, rollbackScript("V59__annual_report_h5_rollback.sql"));
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            assertThat(count(connection, schema, "TABLES", "crm_annual_report%")).isZero();
            assertThat(count(connection, schema, "TRIGGERS", "trg_annual_%")).isZero();
            assertThat(count(connection, schema, "TABLES", "crm_customer")).isEqualTo(1);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void nonEmptyHistoryRefusesRollbackAndPreservesTables() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO sys_user (id, tenant_id, org_id, username, display_name, user_type, status) "
                    + "VALUES (9001, 1, 1, 'rb_tester', '测试员工', 'EMPLOYEE', 'ACTIVE') "
                    + "ON DUPLICATE KEY UPDATE username = username");
            st.executeUpdate("INSERT INTO crm_customer (id, tenant_id, owner_staff_id, customer_no, display_name) "
                    + "VALUES (9001, 1, 9001, 'CUS-RB-1', '测试客户')");
            st.executeUpdate("INSERT INTO crm_annual_report (tenant_id, customer_id, owner_staff_id, created_by, "
                    + "report_year, share_key, tone_code, tone_reason, display_name_snapshot, tier_label_snapshot, "
                    + "butler_name_snapshot, snapshot_json) VALUES (1, 9001, 9001, 9001, 2026, "
                    + "'1234567890123456789012345678901234567890123', 'POETIC', 'test', '测试客户', 'S', '管家', '{}')");

            ExecResult refused = runMysql(schema, rollbackScript("V59__annual_report_h5_rollback.sql"));
            assertThat(refused.getExitCode()).isNotZero();
            assertThat(refused.getStderr()).contains("V59 rollback blocked: annual report or visit history is non-empty");
            assertThat(count(connection, schema, "TABLES", "crm_annual_report%")).isEqualTo(3);
            assertThat(count(connection, schema, "TRIGGERS", "trg_annual_%")).isEqualTo(3);
        } finally {
            dropSchema(schema);
        }
    }

    private String newSchema() throws Exception {
        String schema = "annual_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        return schema;
    }

    private String migrate(String schema) {
        String url = MYSQL.getJdbcUrl().replace("/medical_crm", "/" + schema);
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
        return url;
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private String rollbackScript(String filename) throws Exception {
        return new ClassPathResource("db/rollback/" + filename).getContentAsString(StandardCharsets.UTF_8);
    }

    private ExecResult runMysql(String schema, String script) throws Exception {
        return MYSQL.execInContainer("mysql", "--protocol=tcp", "-h", "127.0.0.1", "-u", "root",
                "-p" + MYSQL.getPassword(), "--default-character-set=utf8mb4", schema, "-e", script);
    }

    private long count(Connection connection, String schema, String objectType, String pattern) throws Exception {
        String table = "TABLES".equals(objectType) ? "TABLES" : "TRIGGERS";
        String schemaColumn = "TABLES".equals(objectType) ? "TABLE_SCHEMA" : "TRIGGER_SCHEMA";
        String nameColumn = "TABLES".equals(objectType) ? "TABLE_NAME" : "TRIGGER_NAME";
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema." + table + " WHERE " + schemaColumn + "='" + schema
                             + "' AND " + nameColumn + " LIKE '" + pattern + "'")) {
            result.next();
            return result.getLong(1);
        }
    }
}
