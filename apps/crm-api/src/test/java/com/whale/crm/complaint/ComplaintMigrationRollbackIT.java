package com.whale.crm.complaint;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ComplaintMigrationRollbackIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Test
    void freshSchemaMigratesAndRollsBackOnlyComplaintObjects() throws Exception {
        String schema = "complaint_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String rootPassword = MYSQL.getPassword();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/medical_crm", "/" + schema);
        try {
            Flyway flyway = Flyway.configure().dataSource(url, "root", rootPassword)
                    .locations("classpath:db/migration").baselineOnMigrate(true).load();
            flyway.migrate();
            try (Connection connection = DriverManager.getConnection(url, "root", rootPassword)) {
                assertThat(count(connection, schema, "TABLES", "crm_complaint_%")).isEqualTo(7);
                assertThat(count(connection, schema, "TRIGGERS", "trg_complaint_%")).isEqualTo(12);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/rollback/V41__complaint_empathy_script_library_rollback.sql"));
                assertThat(count(connection, schema, "TABLES", "crm_complaint_%")).isEqualTo(6);
                assertThat(count(connection, schema, "TRIGGERS", "trg_complaint_%")).isEqualTo(10);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/rollback/V31__complaint_automation_rollback.sql"));
                assertThat(count(connection, schema, "TABLES", "crm_complaint_%")).isZero();
                assertThat(count(connection, schema, "TRIGGERS", "trg_complaint_%")).isZero();
                assertThat(count(connection, schema, "TABLES", "sys_role")).isEqualTo(1);
                assertThat(count(connection, schema, "TABLES", "crm_notification")).isEqualTo(1);
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private long count(Connection connection, String schema, String objectType, String pattern) throws Exception {
        String table = "TABLES".equals(objectType) ? "TABLES" : "TRIGGERS";
        String schemaColumn = "TABLES".equals(objectType) ? "TABLE_SCHEMA" : "TRIGGER_SCHEMA";
        String nameColumn = "TABLES".equals(objectType) ? "TABLE_NAME" : "TRIGGER_NAME";
        String sql = "SELECT COUNT(*) FROM information_schema." + table + " WHERE " + schemaColumn
                + "='" + schema + "' AND " + nameColumn + " LIKE '" + pattern + "'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
