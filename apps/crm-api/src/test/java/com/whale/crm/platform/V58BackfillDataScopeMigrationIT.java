package com.whale.crm.platform;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V58BackfillDataScopeMigrationIT {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
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
    void v58BackfillsMissingEmployeeScopesWithoutOverwritingExistingOrTouchingInstitutionUsers() throws Exception {
        String schema = "v58_scope_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("57").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // 准备组织、团队与各角色存量用户（模拟 V58 之前未写 sys_data_scope 的场景）
            jdbc.update("INSERT IGNORE INTO sys_org (id,tenant_id,org_code,org_name,status) "
                    + "VALUES (1,1,'WHALE','鲸选美学','ACTIVE')");
            jdbc.update("INSERT IGNORE INTO sys_team (id,tenant_id,org_id,team_name,status) "
                    + "VALUES (1,1,1,'管家一组','ACTIVE')");
            for (String[] role : List.of(
                    new String[]{"1", "ADMIN", "系统管理员"},
                    new String[]{"3", "MANAGER_LEAD", "管家主管"},
                    new String[]{"4", "MANAGER", "管家"},
                    new String[]{"5", "BUSINESS", "商务运营"},
                    new String[]{"7", "INSTITUTION_STAFF", "机构操作员"})) {
                jdbc.update("INSERT IGNORE INTO sys_role (id,tenant_id,role_code,role_name,status) "
                                + "VALUES (?,1,?,?,'ACTIVE')",
                        Long.parseLong(role[0]), role[1], role[2]);
            }

            long mgrId = insertUser(jdbc, "legacy_mgr", "EMPLOYEE", 1L);
            assignRole(jdbc, mgrId, "MANAGER");

            long leadWithTeamId = insertUser(jdbc, "legacy_lead_team", "EMPLOYEE", 1L);
            assignRole(jdbc, leadWithTeamId, "MANAGER_LEAD");

            long leadNoTeamId = insertUser(jdbc, "legacy_lead_noteam", "EMPLOYEE", null);
            assignRole(jdbc, leadNoTeamId, "MANAGER_LEAD");

            long adminPlusMgrId = insertUser(jdbc, "legacy_admin_mgr", "EMPLOYEE", 1L);
            assignRole(jdbc, adminPlusMgrId, "MANAGER");
            assignRole(jdbc, adminPlusMgrId, "ADMIN");

            long businessId = insertUser(jdbc, "legacy_business", "EMPLOYEE", null);
            assignRole(jdbc, businessId, "BUSINESS");

            long instId = insertUser(jdbc, "legacy_inst", "INSTITUTION", null);
            assignRole(jdbc, instId, "INSTITUTION_STAFF");

            long existingCustomId = insertUser(jdbc, "legacy_existing", "EMPLOYEE", 1L);
            assignRole(jdbc, existingCustomId, "ADMIN");
            jdbc.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id) "
                    + "VALUES (1,?,'customer','SELF',NULL)", existingCustomId);

            // 执行 V58 迁移
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("58").load().migrate();

            assertThat(scopesOf(jdbc, mgrId))
                    .containsExactlyInAnyOrder("customer:SELF:null", "institution:ALL:null");
            assertThat(scopesOf(jdbc, leadWithTeamId))
                    .containsExactlyInAnyOrder("customer:TEAM:1", "institution:ALL:null");
            assertThat(scopesOf(jdbc, leadNoTeamId))
                    .containsExactlyInAnyOrder("customer:SELF:null", "institution:ALL:null");
            assertThat(scopesOf(jdbc, adminPlusMgrId))
                    .containsExactlyInAnyOrder("customer:ALL:null", "institution:ALL:null");
            assertThat(scopesOf(jdbc, businessId))
                    .containsExactly("institution:ALL:null");
            assertThat(scopesOf(jdbc, instId)).isEmpty();
            // 已有 customer 授权不被覆盖；仅补齐缺失的 institution
            assertThat(scopesOf(jdbc, existingCustomId))
                    .containsExactlyInAnyOrder("customer:SELF:null", "institution:ALL:null");

            // 回滚脚本可执行，且重新应用 V58 完全幂等不产生重复行
            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V58__backfill_user_default_data_scopes_rollback.sql"));
            }
            jdbc.update("DELETE FROM flyway_schema_history WHERE version='58'");
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("58").load().migrate();

            assertThat(scopesOf(jdbc, mgrId))
                    .containsExactlyInAnyOrder("customer:SELF:null", "institution:ALL:null");
            assertThat(scopesOf(jdbc, leadWithTeamId))
                    .containsExactlyInAnyOrder("customer:TEAM:1", "institution:ALL:null");
        } finally {
            try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private long insertUser(JdbcTemplate jdbc, String username, String userType, Long teamId) {
        jdbc.update("INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,"
                        + "user_type,status) VALUES (1,1,?,?,'测试','hash',?,'ACTIVE')",
                teamId, username, userType);
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE tenant_id=1 AND username=?",
                Long.class, username);
        assertThat(id).isNotNull();
        return id;
    }

    private void assignRole(JdbcTemplate jdbc, long userId, String roleCode) {
        jdbc.update("INSERT INTO sys_user_role (tenant_id,user_id,role_id) "
                        + "SELECT 1, ?, id FROM sys_role WHERE tenant_id=1 AND role_code=?",
                userId, roleCode);
    }

    private List<String> scopesOf(JdbcTemplate jdbc, long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT resource, scope_type, scope_ref_id FROM sys_data_scope WHERE tenant_id=1 AND user_id=?",
                userId);
        return rows.stream()
                .map(row -> row.get("resource") + ":" + row.get("scope_type") + ":" + row.get("scope_ref_id"))
                .toList();
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        ds.setUser("root");
        ds.setPassword(MYSQL.getPassword());
        return ds;
    }
}
