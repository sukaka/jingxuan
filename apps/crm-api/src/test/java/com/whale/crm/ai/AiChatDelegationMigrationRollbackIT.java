package com.whale.crm.ai;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatDelegationMigrationRollbackIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Test
    void emptyHistoryAllowsOrderedRollbackWithoutTouchingOtherModules() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try {
            assertThat(runMysql(schema, rollbackScript("V39__ai_chat_send_fence_rollback.sql")).getExitCode()).isZero();
            assertThat(runMysql(schema, rollbackScript("V36__ai_chat_delegation_rollback.sql")).getExitCode()).isZero();
            try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
                assertThat(count(connection, schema, "TABLES", "crm_ai_chat_delegation_%")).isZero();
                assertThat(count(connection, schema, "TABLES", "crm_ai_chat_send_fence")).isZero();
                assertThat(count(connection, schema, "TRIGGERS", "trg_ai_delegation_%")).isZero();
                assertThat(count(connection, schema, "TABLES", "crm_ai_job")).isEqualTo(1);
                assertThat(count(connection, schema, "TABLES", "crm_wecom_message")).isEqualTo(1);
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void rollbackRefusesToDestroyAnyRunOrAttemptHistory() throws Exception {
        String schema = newSchema();
        String url = migrate(schema);
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,"
                    + "external_userid,staff_user_id,message_type,sender_type,sent_at,content_object_key,"
                    + "content_sha256,consent_snapshot,status) VALUES (1,'corp','msg',1,'external',8,"
                    + "'TEXT','CUSTOMER','2026-01-01 00:00:00','object','" + "0".repeat(64)
                    + "','GRANTED','STORED')");
            long messageId = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO crm_ai_chat_delegation_run (tenant_id,source_message_id,"
                    + "staff_user_id,corp_id,external_userid,status) VALUES (1," + messageId
                    + ",8,'corp','external','QUEUED')");
            long runId = lastInsertId(statement);
            statement.executeUpdate("INSERT INTO crm_ai_chat_delegation_attempt (tenant_id,run_id,phase,result) "
                    + "VALUES (1," + runId + ",'RECEIVE','QUEUED')");

            ExecResult refused = runMysql(schema, rollbackScript("V36__ai_chat_delegation_rollback.sql"));
            assertThat(refused.getExitCode()).isNotZero();
            assertThat(refused.getStderr()).contains("AI delegation history exists; rollback refused");
            assertThat(count(connection, schema, "TABLES", "crm_ai_chat_delegation_run")).isEqualTo(1);
            assertThat(count(connection, schema, "TABLES", "crm_ai_chat_delegation_attempt")).isEqualTo(1);
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM crm_ai_chat_delegation_run")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM crm_ai_chat_delegation_attempt")) {
                result.next();
                assertThat(result.getLong(1)).isEqualTo(1);
            }
        } finally {
            dropSchema(schema);
        }
    }

    private String newSchema() throws Exception {
        String schema = "ai_delegation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        return schema;
    }

    private String migrate(String schema) {
        String url = MYSQL.getJdbcUrl().replace("/medical_crm", "/" + schema);
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword())
                .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate();
        return url;
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private String rollbackScript(String filename) throws Exception {
        return new ClassPathResource("db/rollback/" + filename)
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ExecResult runMysql(String schema, String script) throws Exception {
        return MYSQL.execInContainer("mysql", "--protocol=tcp", "-h", "127.0.0.1", "-u", "root",
                "-p" + MYSQL.getPassword(), "--default-character-set=utf8mb4", schema, "-e", script);
    }

    private long lastInsertId(Statement statement) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT LAST_INSERT_ID()")) {
            result.next();
            return result.getLong(1);
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
