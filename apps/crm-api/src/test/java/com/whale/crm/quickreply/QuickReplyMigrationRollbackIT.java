package com.whale.crm.quickreply;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuickReplyMigrationRollbackIT {
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
    void freshSchemaMigratesAndRollbackRemovesOnlyQuickReplyObjects() throws Exception {
        String schema = "quick_reply_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String rootPassword = MYSQL.getPassword();
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", rootPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        String url = MYSQL.getJdbcUrl().replace("/medical_crm", "/" + schema);
        try {
            Flyway.configure().dataSource(url, "root", rootPassword)
                    .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
            try (Connection connection = DriverManager.getConnection(url, "root", rootPassword)) {
                assertThat(count(connection, schema, "TABLES", "crm_quick_reply_%")).isEqualTo(5);
                assertThat(count(connection, schema, "TRIGGERS", "trg_quick_reply_%")).isEqualTo(2);
                assertThat(count(connection, schema, "TABLES", "crm_ai_reply_suggestion")).isEqualTo(1);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/rollback/V46__ai_reply_suggestion_rollback.sql"));
                assertThat(count(connection, schema, "TABLES", "crm_ai_reply_suggestion")).isZero();
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/rollback/V45__quick_reply_engagement_rollback.sql"));
                assertThat(count(connection, schema, "TABLES", "crm_quick_reply_%")).isEqualTo(4);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                        "db/rollback/V44__quick_reply_foundation_rollback.sql"));
                assertThat(count(connection, schema, "TABLES", "crm_quick_reply_%")).isZero();
                assertThat(count(connection, schema, "TRIGGERS", "trg_quick_reply_%")).isZero();
                assertThat(count(connection, schema, "TABLES", "crm_customer")).isEqualTo(1);
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
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema." + table + " WHERE " + schemaColumn + "='" + schema
                        + "' AND " + nameColumn + " LIKE '" + pattern + "'")) {
            result.next();
            return result.getLong(1);
        }
    }
}
