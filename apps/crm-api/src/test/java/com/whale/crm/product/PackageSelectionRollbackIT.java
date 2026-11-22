package com.whale.crm.product;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Container.ExecResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PackageSelectionRollbackIT {
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
    void emptySelectionSnapshotsAllowRollback() throws Exception {
        String schema = newSchema();
        String url = url(schema);
        try {
            migrate(url);
            String script = rollbackScript();
            assertThat(runMysql(schema, script).getExitCode()).isZero();
            try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
                assertThat(count(connection, schema, "TABLES", "crm_order_item_selection")).isZero();
                assertThat(count(connection, schema, "TABLES", "crm_package_selection_group")).isZero();
                assertThat(count(connection, schema, "TRIGGERS", "trg_order_item_selection_%")).isZero();
                assertThat(referencePriceColumns(connection, schema)).isZero();
                assertThat(count(connection, schema, "TABLES", "crm_order_item")).isEqualTo(1);
            }
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void nonEmptySelectionSnapshotsSignalBeforeAnyDrop() throws Exception {
        String schema = newSchema();
        String url = url(schema);
        try {
            migrate(url);
            try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                 Statement statement = connection.createStatement()) {
                insertSelectionSnapshot(statement);
            }
            String script = rollbackScript();
            ExecResult result = runMysql(schema, script);

            assertThat(result.getExitCode()).isNotZero();
            assertThat(result.getStderr()).contains("V34 rollback blocked: order item selection snapshots exist");
            try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
                assertThat(count(connection, schema, "TABLES", "crm_order_item_selection")).isEqualTo(1);
                assertThat(count(connection, schema, "TABLES", "crm_package_selection_group")).isEqualTo(1);
                assertThat(count(connection, schema, "TRIGGERS", "trg_order_item_selection_%")).isEqualTo(2);
                assertThat(referencePriceColumns(connection, schema)).isEqualTo(1);
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void migrate(String url) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword())
                .locations("classpath:db/migration").target("34").baselineOnMigrate(true).load().migrate();
    }

    private String newSchema() throws Exception {
        String schema = "package_rollback_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        return schema;
    }

    private String url(String schema) {
        return MYSQL.getJdbcUrl().replace("/medical_crm", "/" + schema);
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private String rollbackScript() throws Exception {
        return new ClassPathResource("db/rollback/V34__package_selection_groups_rollback.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ExecResult runMysql(String schema, String script) throws Exception {
        return MYSQL.execInContainer("mysql", "--protocol=tcp", "-h", "127.0.0.1", "-u", "root",
                "-p" + MYSQL.getPassword(), "--default-character-set=utf8mb4", schema, "-e", script);
    }

    private void insertSelectionSnapshot(Statement statement) throws Exception {
        statement.executeUpdate("INSERT INTO crm_project (id,tenant_id,project_code,project_name,list_price_fen,status) "
                + "VALUES (101,1,'ROLLBACK-P','回滚测试项目',100,'ACTIVE')");
        statement.executeUpdate("INSERT INTO crm_package (id,tenant_id,package_code,package_name,status) "
                + "VALUES (201,1,'ROLLBACK-PKG','回滚测试套餐','ACTIVE')");
        statement.executeUpdate("INSERT INTO crm_package_version "
                + "(id,tenant_id,package_id,version_no,selection_mode,choice_count,status) "
                + "VALUES (401,1,201,1,'CHOOSE_N',1,'ACTIVE')");
        statement.executeUpdate("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,customer_no,nickname) "
                + "VALUES (901,1,1,'ROLLBACK-C901','回滚测试客户')");
        statement.executeUpdate("INSERT INTO crm_order "
                + "(id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,source_system,order_type_code,"
                + "status,receiver_type,payable_fen,paid_fen,refunded_fen) "
                + "VALUES (901,1,'ROLLBACK-O901',901,1,1,'CRM','REGULAR','PAID','PLATFORM',100,100,0)");
        statement.executeUpdate("INSERT INTO crm_order_item "
                + "(id,tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,purchase_quantity,"
                + "list_amount_fen,paid_allocated_fen) VALUES (901,1,901,1,401,'回滚快照',1,100,0)");
        statement.executeUpdate("INSERT INTO crm_order_item_selection "
                + "(tenant_id,order_item_id,package_version_id,group_code,group_name,min_selections,max_selections,"
                + "project_id,project_code_snapshot,project_name_snapshot,grant_count,reference_price_fen,sort_order) "
                + "VALUES (1,901,401,'A','A组',1,1,101,'ROLLBACK-P','回滚测试项目',1,100,1)");
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

    private long referencePriceColumns(Connection connection, String schema) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='" + schema
                        + "' AND table_name='crm_package_project' AND column_name='reference_price_fen'")) {
            result.next();
            return result.getLong(1);
        }
    }
}
