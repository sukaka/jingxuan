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

class V60YouzanAnnualRollbackIT {

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
    void emptyV60TablesAllowCleanRollbackWithoutTouchingV59() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            assertThat(countV60Tables(connection, schema)).isEqualTo(5);
            ExecResult result = runMysql(schema, rollbackScript());
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            assertThat(countV60Tables(connection, schema)).isZero();
            assertThat(countTables(connection, schema, "crm_annual_report")).isEqualTo(1);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void nonEmptyV60SnapshotRefusesRollback() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             Statement st = connection.createStatement()) {
            st.executeUpdate("INSERT INTO crm_youzan_annual_profile (tenant_id, shop_id, yz_open_id, display_name, "
                    + "synced_at) VALUES (1, 'shop_1', 'yz_1', '测试买家', UTC_TIMESTAMP(3))");
            ExecResult refused = runMysql(schema, rollbackScript());
            assertThat(refused.getExitCode()).isNotZero();
            assertThat(refused.getStderr()).contains("V60 rollback blocked");
            assertThat(countV60Tables(connection, schema)).isEqualTo(5);
        } finally {
            dropSchema(schema);
        }
    }

    private String newSchema() throws Exception {
        String schema = "v60_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private String rollbackScript() throws Exception {
        return new ClassPathResource("db/rollback/V60__youzan_annual_snapshot_and_c_auth_rollback.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private ExecResult runMysql(String schema, String script) throws Exception {
        return MYSQL.execInContainer("mysql", "--protocol=tcp", "-h", "127.0.0.1", "-u", "root",
                "-p" + MYSQL.getPassword(), "--default-character-set=utf8mb4", schema, "-e", script);
    }

    private long countV60Tables(Connection connection, String schema) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema
                + "' AND TABLE_NAME IN ('crm_youzan_annual_profile','crm_youzan_annual_order_snapshot',"
                + "'crm_h5_c_identity_binding','crm_h5_sms_verification','crm_h5_c_visit_session')";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long countTables(Connection connection, String schema, String tableName) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema
                + "' AND TABLE_NAME='" + tableName + "'";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
