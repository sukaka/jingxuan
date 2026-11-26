package com.whale.crm.platform.config;

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

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReferenceDataIT {
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
    void cleanProductionHasCoreReferenceDataAndRepeatSeedPreservesOperationsChanges() throws Exception {
        MysqlDataSource dataSource = dataSourceFor("medical_crm");
        productionFlyway(dataSource).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> dictTypes = jdbc.queryForList(
                "SELECT dict_code FROM sys_dictionary_type WHERE tenant_id=1 AND status='ACTIVE'",
                String.class);
        assertThat(dictTypes).containsExactlyInAnyOrder(
                "cities", "institutionBusinessTypes", "institutionOwnerships", "merchantGrades",
                "settlementTypes", "customerStatuses", "customerSources", "customerChannels",
                "orderTypes", "communicationChannels", "taskTypes", "refundReasons",
                "materialTypes", "customerTiers", "customerTags");

        List<String> options = jdbc.queryForList("SELECT CONCAT(t.dict_code,':',i.option_code) "
                + "FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "AND t.tenant_id=i.tenant_id WHERE i.tenant_id=1 AND i.status='ACTIVE'", String.class);
        assertThat(options).contains(
                "cities:BJ", "cities:SH", "cities:GZ", "cities:SZ", "cities:HZ", "cities:CD",
                "cities:CQ", "cities:NJ", "cities:WH", "cities:SUZ", "cities:TJ", "cities:XA", "cities:QD",
                "cities:CS", "cities:ZZ", "cities:XM", "cities:KM",
                "orderTypes:REGULAR", "orderTypes:FLASH", "orderTypes:PROMOTION",
                "institutionBusinessTypes:MED_AESTHETIC", "institutionOwnerships:PRIVATE", "merchantGrades:B",
                "settlementTypes:MONTHLY", "settlementTypes:PER_REDEMPTION");
        List<String> cityOptions = options.stream().filter(item -> item.startsWith("cities:")).toList();
        assertThat(cityOptions).hasSize(49).doesNotHaveDuplicates();
        assertThat(options).noneMatch(item -> item.startsWith("customerStatuses:"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isZero();

        List<String> newPermissions = jdbc.queryForList(
                "SELECT permission_code FROM sys_permission WHERE tenant_id=1 AND permission_code IN "
                        + "('wecom:mapping:manage','followup:policy:manage','channel:approve_finance')",
                String.class);
        assertThat(newPermissions).containsExactlyInAnyOrder(
                "wecom:mapping:manage", "followup:policy:manage", "channel:approve_finance");

        assertThat(rolePermissions(jdbc, "ADMIN")).contains(
                "wecom:mapping:manage", "followup:policy:manage", "channel:approve_finance",
                "customer:tag:manage", "customer:source_channel:manage");
        assertThat(rolePermissions(jdbc, "MANAGER_LEAD")).contains("customer:tag:manage", "customer:source_channel:manage")
                .doesNotContain("dictionary:write");
        assertThat(rolePermissions(jdbc, "MANAGER")).doesNotContain("customer:tag:manage",
                "customer:source_channel:manage", "dictionary:write");
        assertThat(rolePermissions(jdbc, "OPERATION_LEAD")).contains("followup:policy:manage");
        assertThat(rolePermissions(jdbc, "FINANCE")).contains("channel:approve_finance");

        String msgEnabledDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='crm_wecom_staff_account' "
                        + "AND column_name='message_enabled'",
                String.class);
        assertThat(msgEnabledDefault).isEqualTo("0");

        String idempotencyUniqueCols = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name='crm_idempotency_record' "
                        + "AND index_name='uk_idempotency_reco_1'",
                String.class);
        assertThat(idempotencyUniqueCols).isEqualTo("tenant_id,scope_code,actor_user_id,idempotency_key");

        List<String> v52Indexes = jdbc.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND index_name IN ("
                        + "'idx_login_session_token_hash','idx_login_session_expires_at',"
                        + "'idx_outbox_relay_status','idx_ai_job_status_claim',"
                        + "'idx_export_job_status_lease','idx_knowledge_job_status_claim',"
                        + "'idx_wecom_media_status_claim')",
                String.class);
        assertThat(v52Indexes).containsExactlyInAnyOrder(
                "idx_login_session_token_hash", "idx_login_session_expires_at",
                "idx_outbox_relay_status", "idx_ai_job_status_claim",
                "idx_export_job_status_lease", "idx_knowledge_job_status_claim",
                "idx_wecom_media_status_claim");

        jdbc.update("UPDATE sys_dictionary_type SET dict_name='运营自定义城市',status='INACTIVE' "
                + "WHERE tenant_id=1 AND dict_code='cities'");
        jdbc.update("UPDATE sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "SET i.option_label='运营自定义北京',i.sort_no=99,i.status='INACTIVE' "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='cities' AND i.option_code='BJ'");
        jdbc.update("UPDATE sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "SET i.option_label='运营自定义订单',i.sort_no=88,i.status='INACTIVE' "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='orderTypes' AND i.option_code='REGULAR'");
        productionFlyway(dataSource).migrate();
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/prod/R__seed_reference_prod.sql"));
        }

        Map<String, Object> customizedType = jdbc.queryForMap(
                "SELECT dict_name,status FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'");
        assertThat(customizedType).containsEntry("dict_name", "运营自定义城市").containsEntry("status", "INACTIVE");

        Map<String, Object> customizedCity = jdbc.queryForMap("SELECT i.option_label,i.sort_no,i.status "
                + "FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='cities' AND i.option_code='BJ'");
        assertThat(customizedCity).containsEntry("option_label", "运营自定义北京")
                .containsEntry("sort_no", 99).containsEntry("status", "INACTIVE");

        Map<String, Object> customized = jdbc.queryForMap("SELECT i.option_label,i.sort_no,i.status "
                + "FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='orderTypes' AND i.option_code='REGULAR'");
        assertThat(customized).containsEntry("option_label", "运营自定义订单")
                .containsEntry("sort_no", 88).containsEntry("status", "INACTIVE");
    }

    @Test
    void v52RollbackRevertsIndexesActorColumnDefaultAndPermissions() throws Exception {
        String schema = "v52_rollback_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource rollbackDs = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(rollbackDs).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            try (Connection conn = rollbackDs.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V52__platform_security_and_indexes_rollback.sql"));
            }
            JdbcTemplate jdbc = new JdbcTemplate(rollbackDs);
            Long remainingIndexes = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                            + "WHERE table_schema=? AND index_name IN ("
                            + "'idx_login_session_token_hash','idx_login_session_expires_at',"
                            + "'idx_outbox_relay_status','idx_ai_job_status_claim',"
                            + "'idx_export_job_status_lease','idx_knowledge_job_status_claim',"
                            + "'idx_wecom_media_status_claim')",
                    Long.class, schema);
            assertThat(remainingIndexes).isZero();
            Long actorCol = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='crm_idempotency_record' "
                            + "AND column_name='actor_user_id'",
                    Long.class, schema);
            assertThat(actorCol).isZero();
            String msgDefault = jdbc.queryForObject(
                    "SELECT column_default FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='crm_wecom_staff_account' "
                            + "AND column_name='message_enabled'",
                    String.class, schema);
            assertThat(msgDefault).isEqualTo("1");
            Long remainingPerms = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_permission WHERE permission_code IN "
                            + "('wecom:mapping:manage','followup:policy:manage','channel:approve_finance')",
                    Long.class);
            assertThat(remainingPerms).isZero();
        } finally {
            try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void v54CreatesAndRollsBackChannelChangeRequestTable() throws Exception {
        String schema = "v54_rollback_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource rollbackDs = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(rollbackDs).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(rollbackDs);
            Long tableCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=? AND table_name='crm_channel_change_request'",
                    Long.class, schema);
            assertThat(tableCount).isEqualTo(1L);
            String activeUkCols = jdbc.queryForObject(
                    "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                            + "WHERE table_schema=? AND table_name='crm_channel_change_request' "
                            + "AND index_name='uk_channel_change_request_active'",
                    String.class, schema);
            assertThat(activeUkCols).isEqualTo("tenant_id,active_channel_id");

            try (Connection conn = rollbackDs.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V54__channel_change_requests_and_crm_fixes_rollback.sql"));
            }
            Long remainingTable = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema=? AND table_name='crm_channel_change_request'",
                    Long.class, schema);
            assertThat(remainingTable).isZero();
        } finally {
            try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void v55RepairsDirectRecallAndAddsArchiveScopeAndLeasesAndRollsBackCleanly() throws Exception {
        String schema = "v55_rollback_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("54").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (1,1,'WHALE','鲸选','ACTIVE')");
            jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                    + "VALUES (2,1,1,'staff2','员工2','hash','EMPLOYEE','ACTIVE'),"
                    + "(999,1,1,'unmapped','未映射员工','hash','EMPLOYEE','ACTIVE')");
            jdbc.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                    + "VALUES (1,'ww-v55','staff-2',2,1)");
            jdbc.update("INSERT INTO crm_wecom_cursor (tenant_id,corp_id,archive_scope,last_seq,status,data_gap) "
                    + "VALUES (1,'ww-v55','OFFICIAL_ARCHIVE',101,'SUCCESS',0)");
            jdbc.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,external_userid,"
                    + "staff_user_id,conversation_type,conversation_id,message_type,sender_type,sender_wecom_userid,"
                    + "sender_staff_user_id,sent_at,content_object_key,content_sha256,consent_snapshot,status) "
                    + "VALUES (1,'ww-v55','official-101',101,NULL,'wm-cust-1',2,'DIRECT','wm-cust-1','TEXT','CUSTOMER',"
                    + "'wm-cust-1',NULL,UTC_TIMESTAMP(3),'obj-101','" + "a".repeat(64) + "','GRANTED','UNMATCHED')");

            jdbc.update("INSERT INTO crm_wecom_recall_event (tenant_id,corp_id,recall_message_id,original_message_id,"
                    + "recall_seq,external_userid,staff_user_id,conversation_type,conversation_id,sender_type,"
                    + "sender_wecom_userid,occurred_at,status) "
                    + "VALUES (1,'ww-v55','rec-emp','orig-emp',102,'wm-cust-1',2,'DIRECT','staff-2','EMPLOYEE',"
                    + "'staff-2',UTC_TIMESTAMP(3),'APPLIED')");
            jdbc.update("INSERT INTO crm_wecom_recall_event (tenant_id,corp_id,recall_message_id,original_message_id,"
                    + "recall_seq,external_userid,staff_user_id,conversation_type,conversation_id,sender_type,"
                    + "sender_wecom_userid,occurred_at,status) "
                    + "VALUES (1,'ww-v55','rec-unmapped','orig-unmapped',103,'wm-cust-2',999,'DIRECT',NULL,'EMPLOYEE',"
                    + "NULL,UTC_TIMESTAMP(3),'APPLIED')");
            jdbc.update("INSERT INTO crm_wecom_recall_event (tenant_id,corp_id,recall_message_id,original_message_id,"
                    + "recall_seq,external_userid,staff_user_id,conversation_type,conversation_id,sender_type,"
                    + "sender_wecom_userid,occurred_at,status) "
                    + "VALUES (1,'ww-v55','rec-cust','orig-cust',104,'wm-cust-3',2,'DIRECT','wm-cust-3','CUSTOMER',"
                    + "NULL,UTC_TIMESTAMP(3),'APPLIED')");
            jdbc.update("INSERT INTO crm_wecom_recall_event (tenant_id,corp_id,recall_message_id,original_message_id,"
                    + "recall_seq,external_userid,staff_user_id,conversation_type,conversation_id,sender_type,"
                    + "sender_wecom_userid,occurred_at,status) "
                    + "VALUES (1,'ww-v55','rec-room','orig-room',105,NULL,NULL,'ROOM','wr-room-1','CUSTOMER',"
                    + "'wm-room-cust',UTC_TIMESTAMP(3),'APPLIED')");

            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();

            assertThat(jdbc.queryForObject("SELECT archive_scope FROM crm_wecom_message WHERE msg_id='official-101'",
                    String.class)).isEqualTo("OFFICIAL_ARCHIVE");

            jdbc.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,archive_scope,customer_id,"
                    + "external_userid,staff_user_id,conversation_type,conversation_id,message_type,sender_type,"
                    + "sender_wecom_userid,sender_staff_user_id,sent_at,content_object_key,content_sha256,"
                    + "consent_snapshot,status) "
                    + "VALUES (1,'ww-v55','mock-101',101,'MOCK_ARCHIVE',NULL,'wm-cust-1',2,'DIRECT','wm-cust-1','TEXT',"
                    + "'CUSTOMER','wm-cust-1',NULL,UTC_TIMESTAMP(3),'obj-m101','" + "b".repeat(64) + "','GRANTED','UNMATCHED')");

            assertThat(jdbc.queryForObject("SELECT conversation_id FROM crm_wecom_recall_event WHERE recall_message_id='rec-emp'",
                    String.class)).isEqualTo("wm-cust-1");
            assertThat(jdbc.queryForObject("SELECT conversation_id FROM crm_wecom_recall_event WHERE recall_message_id='rec-unmapped'",
                    String.class)).isEqualTo("wm-cust-2");
            assertThat(jdbc.queryForObject("SELECT conversation_id FROM crm_wecom_recall_event WHERE recall_message_id='rec-cust'",
                    String.class)).isEqualTo("wm-cust-3");
            assertThat(jdbc.queryForObject("SELECT sender_wecom_userid FROM crm_wecom_recall_event WHERE recall_message_id='rec-cust'",
                    String.class)).isEqualTo("wm-cust-3");
            assertThat(jdbc.queryForObject("SELECT conversation_id FROM crm_wecom_recall_event WHERE recall_message_id='rec-room'",
                    String.class)).isEqualTo("wr-room-1");

            jdbc.update("DELETE FROM crm_wecom_message WHERE msg_id='mock-101'");
            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V55__wecom_archive_scope_and_recall_repair_rollback.sql"));
            }

            Long remainingCols = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? "
                            + "AND ((table_name='crm_wecom_message' AND column_name='archive_scope') "
                            + "OR (table_name='crm_wecom_contact_sync_run' AND column_name='lease_until') "
                            + "OR (table_name='crm_wecom_callback_event' AND column_name='lease_until'))",
                    Long.class, schema);
            assertThat(remainingCols).isZero();
            String ukCols = jdbc.queryForObject(
                    "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                            + "WHERE table_schema=? AND table_name='crm_wecom_message' AND index_name='uk_wecom_message_2'",
                    String.class, schema);
            assertThat(ukCols).isEqualTo("tenant_id,corp_id,archive_seq");
        } finally {
            try (Connection admin = rootDs.getConnection(); Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private List<String> rolePermissions(JdbcTemplate jdbc, String roleCode) {
        return jdbc.queryForList("SELECT p.permission_code FROM sys_role_permission rp "
                + "JOIN sys_role r ON r.tenant_id=rp.tenant_id AND r.id=rp.role_id "
                + "JOIN sys_permission p ON p.tenant_id=rp.tenant_id AND p.id=rp.permission_id "
                + "WHERE rp.tenant_id=1 AND r.role_code=?", String.class, roleCode);
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }

    private Flyway productionFlyway(MysqlDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/prod").load();
    }
}
