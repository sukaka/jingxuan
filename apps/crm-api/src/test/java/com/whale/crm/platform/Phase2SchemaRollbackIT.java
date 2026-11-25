package com.whale.crm.platform;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Phase2SchemaRollbackIT {
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
    void migratesBackfillsVerifiesIndexesRollsBackCleanlyAndReapplies() throws Exception {
        String schema = newSchema("v57_schema_");
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            // 1. 先迁移至 V56，写入覆盖各类边界和脏数据的前置测试样本
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("56").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            seedPreV57Data(jdbc);
            Timestamp aptUpdatedBefore = jdbc.queryForObject(
                    "SELECT updated_at FROM crm_appointment WHERE id=1001", Timestamp.class);
            Long aptVersionBefore = jdbc.queryForObject(
                    "SELECT version FROM crm_appointment WHERE id=1001", Long.class);
            Timestamp materialUpdatedBefore = jdbc.queryForObject(
                    "SELECT updated_at FROM crm_material WHERE id=3001", Timestamp.class);
            Long materialVersionBefore = jdbc.queryForObject(
                    "SELECT version FROM crm_material WHERE id=3001", Long.class);

            // 2. 应用 V57 迁移
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("57").load().migrate();

            // 3. 核验新增表/列/外键/索引/回填与 updated_at/version 保留
            assertV57AppliedState(jdbc, schema);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM crm_appointment WHERE id=1001", Timestamp.class))
                    .isEqualTo(aptUpdatedBefore);
            assertThat(jdbc.queryForObject("SELECT version FROM crm_appointment WHERE id=1001", Long.class))
                    .isEqualTo(aptVersionBefore);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM crm_material WHERE id=3001", Timestamp.class))
                    .isEqualTo(materialUpdatedBefore);
            assertThat(jdbc.queryForObject("SELECT version FROM crm_material WHERE id=3001", Long.class))
                    .isEqualTo(materialVersionBefore);

            // 4. 验证 V57 约束与生成列行为（唯一键判重、274 字符上限、from_seq 默认值、级联删除）
            assertPostV57ConstraintBehaviors(jdbc);

            // 5. 执行 V57 回滚脚本并断言全部结构严格恢复至 V56 状态
            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V57__phase2_high_yield_schema_rollback.sql"));
            }
            assertV57RolledBackState(jdbc, schema);

            // 6. 清除 V57 Flyway 记录后重新应用 V57，验证可重复执行且回填/索引结果完全一致
            jdbc.update("DELETE FROM flyway_schema_history WHERE version='57'");
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("57").load().migrate();
            assertV57AppliedState(jdbc, schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void explainVerifiesPhase2QueriesUseIndexes() throws Exception {
        String schema = newSchema("v57_explain_");
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            seedExplainDataset(ds, jdbc);

            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_appointment WHERE tenant_id=1 "
                            + "AND confirmed_at >= '2026-04-01 00:00:00.000' AND confirmed_at < '2026-04-02 00:00:00.000'",
                    "idx_appointment_confirmed_at");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_appointment WHERE tenant_id=1 "
                            + "AND checked_in_at >= '2026-04-01 00:00:00.000' AND checked_in_at < '2026-04-02 00:00:00.000'",
                    "idx_appointment_checked_in_at");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_appointment WHERE tenant_id=1 "
                            + "AND service_done_at >= '2026-04-01 00:00:00.000' AND service_done_at < '2026-04-02 00:00:00.000'",
                    "idx_appointment_service_done_at");

            Map<String, Object> youzanLatestPagePlan = assertExplainUsesIndex(jdbc,
                    "SELECT latest_event_id FROM crm_youzan_order_latest WHERE tenant_id=1 AND shop_id='shop-exp' "
                            + "ORDER BY latest_event_id DESC LIMIT 20",
                    "idx_youzan_order_latest_event");
            assertThat(String.valueOf(youzanLatestPagePlan.get("Extra")))
                    .contains("Using index")
                    .doesNotContain("Using filesort")
                    .doesNotContain("Using temporary");

            Map<String, Object> youzanLatestCountPlan = assertExplainUsesAnyIndex(jdbc,
                    "SELECT COUNT(*) FROM crm_youzan_order_latest WHERE tenant_id=1 AND shop_id='shop-exp'",
                    List.of("uk_youzan_order_latest_1", "idx_youzan_order_latest_event"));
            assertThat(String.valueOf(youzanLatestCountPlan.get("Extra"))).contains("Using index");

            assertExplainUsesIndex(jdbc,
                    "SELECT latest_event_id FROM crm_youzan_order_latest WHERE tenant_id=1 AND shop_id='shop-exp' "
                            + "AND external_order_no='YZ-EXP-001'",
                    "uk_youzan_order_latest_1");

            Map<String, Object> wecomContactPlan = assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_wecom_contact WHERE tenant_id=1 ORDER BY is_unlinked DESC, id DESC LIMIT 200",
                    "idx_wecom_contact_unlinked");
            assertThat(String.valueOf(wecomContactPlan.get("Extra")))
                    .doesNotContain("Using filesort")
                    .doesNotContain("Using temporary");

            Map<String, Object> materialTagPlan = assertExplainUsesIndex(jdbc,
                    "SELECT material_id FROM crm_material_tag WHERE tenant_id=1 AND tag_code='synthetic'",
                    "idx_material_tag_reverse");
            assertThat(String.valueOf(materialTagPlan.get("Extra"))).contains("Using index");

            Map<String, Object> institutionPlan = assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_institution WHERE tenant_id=1 AND archived=0 AND status='ACTIVE' ORDER BY id DESC LIMIT 20",
                    "idx_institution_archived_status_id");
            assertThat(String.valueOf(institutionPlan.get("Extra"))).doesNotContain("Using filesort");

            Map<String, Object> profileChangePlan = assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_profile_change_request WHERE tenant_id=1 AND status='PENDING_REVIEW' ORDER BY id DESC LIMIT 20",
                    "idx_profile_change_status_id");
            assertThat(String.valueOf(profileChangePlan.get("Extra"))).doesNotContain("Using filesort");
        } finally {
            dropSchema(schema);
        }
    }

    private void seedPreV57Data(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES "
                + "(1,1,'WHALE_T1','鲸选租户1','ACTIVE'), (2,2,'WHALE_T2','鲸选租户2','ACTIVE')");
        jdbc.update("INSERT INTO sys_team (id,tenant_id,org_id,team_name,status) VALUES "
                + "(11,1,1,'顾问一组','ACTIVE'), (21,2,2,'二租户一组','ACTIVE')");
        jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES "
                + "(10,1,1,11,'staff10','管家10','hash','EMPLOYEE','ACTIVE'),"
                + "(20,2,2,21,'staff20','管家20','hash','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name) VALUES "
                + "(101,1,10,11,'CUST-101','客户101'),"
                + "(201,2,20,21,'CUST-201','客户201')");
        jdbc.update("INSERT INTO crm_institution (id,tenant_id,institution_no,full_name,status,settlement_type,archived) VALUES "
                + "(501,1,'INST-501','机构501','ACTIVE','MONTHLY',0),"
                + "(502,2,'INST-502','机构502','ACTIVE','MONTHLY',0)");
        jdbc.update("INSERT INTO crm_file_object (id,tenant_id,object_key,mime_type,size_bytes,sha256,classification,"
                + "scan_status,uploaded_by) VALUES "
                + "(801,1,'mat/t1/801.pdf','application/pdf',1024,'" + "a".repeat(64) + "','MATERIAL','CLEAN',10),"
                + "(802,2,'mat/t2/802.pdf','application/pdf',1024,'" + "b".repeat(64) + "','MATERIAL','CLEAN',20)");

        // 1. crm_appointment + crm_appointment_history（完整流转 + 部分核销重复 SERVICE_DONE + 非标准历史 + 无历史 + 多租户）
        jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                + "scheduled_at,status,created_by,updated_at,version) VALUES "
                + "(1001,1,'APT-1001',101,501,10,'2026-04-01 08:00:00.000','REDEEMED',10,'2026-01-02 03:04:05.000',3),"
                + "(1002,1,'APT-1002',101,501,10,'2026-04-02 08:00:00.000','SERVICE_DONE',10,'2026-01-02 03:04:05.000',1),"
                + "(1003,1,'APT-1003',101,501,10,'2026-04-03 08:00:00.000','CANCELED',10,'2026-01-02 03:04:05.000',0),"
                + "(2001,2,'APT-2001',201,502,20,'2026-04-04 08:00:00.000','CONFIRMED',20,'2026-01-02 03:04:05.000',1)");
        jdbc.update("INSERT INTO crm_appointment_history (tenant_id,appointment_id,from_status,to_status,actor_user_id,created_at) VALUES "
                + "(1,1001,'PENDING_INSTITUTION','CONFIRMED',10,'2026-04-01 08:10:00.000'),"
                + "(1,1001,'CONFIRMED','CHECKED_IN',10,'2026-04-01 09:00:00.000'),"
                + "(1,1001,'CHECKED_IN','SERVICE_DONE',10,'2026-04-01 10:00:00.000'),"
                + "(1,1001,'SERVICE_DONE','SERVICE_DONE',10,'2026-04-01 10:30:00.000'),"
                + "(1,1001,'SERVICE_DONE','REDEEMED',10,'2026-04-01 11:00:00.000'),"
                + "(1,1002,'CONFIRMED','SERVICE_DONE',10,'2026-04-02 10:00:00.000'),"
                + "(2,2001,'PENDING_INSTITUTION','CONFIRMED',20,'2026-04-04 08:15:00.000')");

        // 2. crm_youzan_event（同单多事件 MAX(id) + 跨店铺/跨租户隔离 + 空订单号剔除）
        jdbc.update("INSERT INTO crm_youzan_event (id,tenant_id,shop_id,event_id,event_type,external_order_no,"
                + "payload_masked_json,payload_cipher,source_system,process_status,received_at) VALUES "
                + "(601,1,'shop1','ev-601','ORDER_PENDING','YZ-ORD-1','{}','cipher','WEBHOOK','PROCESSED','2026-04-01 01:00:00.000'),"
                + "(602,1,'shop1','ev-602','ORDER_PAID','YZ-ORD-1','{}','cipher','WEBHOOK','PROCESSED','2026-04-01 02:00:00.000'),"
                + "(603,1,'shop2','ev-603','ORDER_PAID','YZ-ORD-1','{}','cipher','WEBHOOK','PROCESSED','2026-04-01 03:00:00.000'),"
                + "(604,2,'shop1','ev-604','ORDER_PAID','YZ-ORD-1','{}','cipher','WEBHOOK','PROCESSED','2026-04-01 04:00:00.000'),"
                + "(605,1,'shop1','ev-605','ORDER_PAID','','{}','cipher','WEBHOOK','FAILED','2026-04-01 05:00:00.000')");

        // 3. crm_wecom_contact（已关联、未关联、多租户）
        jdbc.update("INSERT INTO crm_wecom_contact (id,tenant_id,corp_id,external_userid,staff_user_id,customer_id,consent_status) VALUES "
                + "(1101,1,'ww-1','ext-linked',10,101,'GRANTED'),"
                + "(1102,1,'ww-1','ext-unlinked',10,NULL,'PENDING'),"
                + "(1103,2,'ww-2','ext-t2-unlinked',20,NULL,'GRANTED')");

        // 4. crm_material + crm_material_revision（多版本取 max(revision_no)、无版本回退 m.tags_json、NULL、[]、脏元素、超长、大小写碰撞）
        String overlongTag = "x".repeat(70);
        String dirtyTags = "[\" tag1 \",\"tag1\",\"\",\"   \",123,null,{\"a\":1},\"Vip\",\"vip\",\"" + overlongTag + "\"]";
        jdbc.update("INSERT INTO crm_material (id,tenant_id,material_no,title,category_code,tags_json,review_status,"
                + "created_by,updated_at,version) VALUES "
                + "(3001,1,'MAT-3001','素材3001','POSTER','[\"old-mat-tag\"]','APPROVED',10,'2026-01-02 03:04:05.000',5),"
                + "(3002,1,'MAT-3002','素材3002','POSTER','[\"fallback-tag\"]','DRAFT',10,'2026-01-02 03:04:05.000',0),"
                + "(3003,1,'MAT-3003','素材3003','POSTER',NULL,'DRAFT',10,'2026-01-02 03:04:05.000',0),"
                + "(3004,1,'MAT-3004','素材3004','POSTER','[\"stale\"]','DRAFT',10,'2026-01-02 03:04:05.000',0),"
                + "(3005,2,'MAT-3005','素材3005','POSTER',NULL,'DRAFT',20,'2026-01-02 03:04:05.000',0)");
        jdbc.update("INSERT INTO crm_material_revision (id,tenant_id,material_id,revision_no,title,category_code,"
                + "tags_json,file_id,status,created_by) VALUES "
                + "(3101,1,3001,1,'素材3001-v1','POSTER','[\"approved-v1\"]',801,'APPROVED',10),"
                + "(3102,1,3001,2,'素材3001-v2','POSTER',?,801,'PENDING',10),"
                + "(3103,1,3003,1,'素材3003-v1','POSTER',NULL,801,'DRAFT',10),"
                + "(3104,1,3004,1,'素材3004-v1','POSTER','[]',801,'DRAFT',10),"
                + "(3105,2,3005,1,'素材3005-v1','POSTER','[\"t2-tag\"]',802,'DRAFT',20)",
                dirtyTags);
        jdbc.update("UPDATE crm_material SET current_revision_id=3101, updated_at=updated_at WHERE id=3001");

        // 5. crm_wecom_data_gap（重复 NULL 碰撞、NULL 与 0 碰撞、非空 seq）
        jdbc.update("INSERT INTO crm_wecom_data_gap (id,tenant_id,corp_id,archive_scope,gap_type,status,from_seq,"
                + "detail_masked,first_seen_at,last_seen_at) VALUES "
                + "(4001,1,'ww-1','CHAT','COLLECTOR_ERROR','OPEN',NULL,'dup-null-1','2026-04-01 00:00:00.000','2026-04-01 00:00:00.000'),"
                + "(4002,1,'ww-1','CHAT','COLLECTOR_ERROR','OPEN',NULL,'dup-null-2','2026-04-01 00:01:00.000','2026-04-01 00:01:00.000'),"
                + "(4003,1,'ww-1','CHAT','CONSENT_REVOKED','OPEN',0,'existing-zero','2026-04-01 00:02:00.000','2026-04-01 00:02:00.000'),"
                + "(4004,1,'ww-1','CHAT','CONSENT_REVOKED','OPEN',NULL,'colliding-null','2026-04-01 00:03:00.000','2026-04-01 00:03:00.000'),"
                + "(4005,1,'ww-1','CHAT','MEMBER_NOT_COVERED','OPEN',10,'non-null-seq','2026-04-01 00:04:00.000','2026-04-01 00:04:00.000')");

        // 6. crm_order（CRM 多条 external_order_no IS NULL + YOUZAN external_shop_id IS NULL）
        jdbc.update("INSERT INTO crm_order (id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "external_order_no,source_system,external_shop_id,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) VALUES "
                + "(5001,1,'ORD-CRM-1',101,10,10,NULL,'CRM',NULL,'REGULAR','PAID','PLATFORM',10000,10000,0),"
                + "(5002,1,'ORD-CRM-2',101,10,10,NULL,'CRM',NULL,'REGULAR','PAID','PLATFORM',10000,10000,0),"
                + "(5003,1,'ORD-YZ-1',101,10,10,'YZ-EXT-1','YOUZAN',NULL,'REGULAR','PAID','PLATFORM',10000,10000,0)");

        // 7. crm_profile_change_request
        jdbc.update("INSERT INTO crm_profile_change_request (id,tenant_id,institution_id,change_type,target_id,base_version,"
                + "target_version,reason,status,current_step,requested_by,requested_at,active_target) VALUES "
                + "(6001,1,501,'PROFILE_FIELD',501,0,0,'测试变更','PENDING_REVIEW','MARKET',10,'2026-04-01 00:00:00.000','INST:501')");
    }

    private void assertV57AppliedState(JdbcTemplate jdbc, String schema) {
        // A. 结构与索引存在性
        assertColumnExists(jdbc, schema, "crm_appointment", "confirmed_at", true);
        assertColumnExists(jdbc, schema, "crm_appointment", "checked_in_at", true);
        assertColumnExists(jdbc, schema, "crm_appointment", "service_done_at", true);
        assertTableExists(jdbc, schema, "crm_youzan_order_latest", true);
        assertColumnExists(jdbc, schema, "crm_wecom_contact", "is_unlinked", true);
        assertColumnExists(jdbc, schema, "crm_material", "latest_revision_id", true);
        assertTableExists(jdbc, schema, "crm_material_tag", true);
        assertColumnExists(jdbc, schema, "crm_order", "external_order_dedupe_key", true);

        Map<String, Object> fromSeqMeta = jdbc.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name='crm_wecom_data_gap' AND column_name='from_seq'",
                schema);
        assertThat(String.valueOf(fromSeqMeta.get("is_nullable"))).isEqualTo("NO");
        assertThat(String.valueOf(fromSeqMeta.get("column_default"))).isEqualTo("0");

        assertForeignKeyExists(jdbc, schema, "crm_youzan_order_latest", "fk_youzan_order_latest_event", true);
        assertForeignKeyExists(jdbc, schema, "crm_material", "fk_material_latest_revision", true);
        assertForeignKeyExists(jdbc, schema, "crm_material_tag", "fk_material_tag_material", true);

        for (Map.Entry<String, String> entry : expectedV57Indexes().entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            assertIndexColumns(jdbc, schema, parts[0], parts[1], entry.getValue());
        }

        // B1. crm_appointment 回填验证（含部分核销 SERVICE_DONE -> SERVICE_DONE 不覆盖首次时间）
        Map<String, Object> apt1001 = jdbc.queryForMap(
                "SELECT confirmed_at, checked_in_at, service_done_at FROM crm_appointment WHERE id=1001");
        assertUtcDateTimeEquals(apt1001.get("confirmed_at"), "2026-04-01T08:10:00Z");
        assertUtcDateTimeEquals(apt1001.get("checked_in_at"), "2026-04-01T09:00:00Z");
        assertUtcDateTimeEquals(apt1001.get("service_done_at"), "2026-04-01T10:00:00Z");

        Map<String, Object> apt1002 = jdbc.queryForMap(
                "SELECT confirmed_at, checked_in_at, service_done_at FROM crm_appointment WHERE id=1002");
        assertThat(apt1002.get("confirmed_at")).isNull();
        assertThat(apt1002.get("checked_in_at")).isNull();
        assertThat(apt1002.get("service_done_at")).isNull();

        Map<String, Object> apt1003 = jdbc.queryForMap(
                "SELECT confirmed_at, checked_in_at, service_done_at FROM crm_appointment WHERE id=1003");
        assertThat(apt1003.get("confirmed_at")).isNull();
        assertThat(apt1003.get("checked_in_at")).isNull();
        assertThat(apt1003.get("service_done_at")).isNull();

        Map<String, Object> apt2001 = jdbc.queryForMap(
                "SELECT confirmed_at, checked_in_at, service_done_at FROM crm_appointment WHERE id=2001");
        assertUtcDateTimeEquals(apt2001.get("confirmed_at"), "2026-04-04T08:15:00Z");
        assertThat(apt2001.get("checked_in_at")).isNull();

        // B2. crm_youzan_order_latest 回填验证
        assertThat(jdbc.queryForObject(
                "SELECT latest_event_id FROM crm_youzan_order_latest WHERE tenant_id=1 AND shop_id='shop1' AND external_order_no='YZ-ORD-1'",
                Long.class)).isEqualTo(602L);
        assertThat(jdbc.queryForObject(
                "SELECT latest_event_id FROM crm_youzan_order_latest WHERE tenant_id=1 AND shop_id='shop2' AND external_order_no='YZ-ORD-1'",
                Long.class)).isEqualTo(603L);
        assertThat(jdbc.queryForObject(
                "SELECT latest_event_id FROM crm_youzan_order_latest WHERE tenant_id=2 AND shop_id='shop1' AND external_order_no='YZ-ORD-1'",
                Long.class)).isEqualTo(604L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_youzan_order_latest WHERE external_order_no=''",
                Long.class)).isZero();

        // B3. crm_wecom_contact.is_unlinked 验证
        assertThat(jdbc.queryForObject("SELECT is_unlinked FROM crm_wecom_contact WHERE id=1101", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT is_unlinked FROM crm_wecom_contact WHERE id=1102", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_unlinked FROM crm_wecom_contact WHERE id=1103", Integer.class)).isEqualTo(1);

        // B4. crm_material.latest_revision_id & crm_material_tag 回填验证
        assertThat(jdbc.queryForObject("SELECT latest_revision_id FROM crm_material WHERE id=3001", Long.class)).isEqualTo(3102L);
        assertThat(jdbc.queryForObject("SELECT latest_revision_id FROM crm_material WHERE id=3002", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT latest_revision_id FROM crm_material WHERE id=3003", Long.class)).isEqualTo(3103L);
        assertThat(jdbc.queryForObject("SELECT latest_revision_id FROM crm_material WHERE id=3004", Long.class)).isEqualTo(3104L);
        assertThat(jdbc.queryForObject("SELECT latest_revision_id FROM crm_material WHERE id=3005", Long.class)).isEqualTo(3105L);

        List<String> mat3001Tags = jdbc.queryForList(
                "SELECT tag_code FROM crm_material_tag WHERE tenant_id=1 AND material_id=3001 ORDER BY tag_code",
                String.class);
        assertThat(mat3001Tags).containsExactly("tag1", "Vip");
        assertThat(jdbc.queryForList(
                "SELECT tag_code FROM crm_material_tag WHERE tenant_id=1 AND material_id=3002", String.class))
                .containsExactly("fallback-tag");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_material_tag WHERE tenant_id=1 AND material_id IN (3003, 3004)", Long.class))
                .isZero();
        assertThat(jdbc.queryForList(
                "SELECT tag_code FROM crm_material_tag WHERE tenant_id=2 AND material_id=3005", String.class))
                .containsExactly("t2-tag");

        // B5. crm_wecom_data_gap.from_seq 回填与去重验证
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_wecom_data_gap WHERE from_seq IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT from_seq FROM crm_wecom_data_gap WHERE id=4001", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND gap_type='COLLECTOR_ERROR'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_wecom_data_gap WHERE tenant_id=1 AND gap_type='CONSENT_REVOKED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT from_seq FROM crm_wecom_data_gap WHERE id=4005", Long.class)).isEqualTo(10L);

        // B6. crm_order.external_order_dedupe_key 验证
        assertThat(jdbc.queryForObject(
                "SELECT external_order_dedupe_key FROM crm_order WHERE id=5001", String.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT external_order_dedupe_key FROM crm_order WHERE id=5003", String.class))
                .isEqualTo("YOUZAN::YZ-EXT-1");
    }

    private void assertPostV57ConstraintBehaviors(JdbcTemplate jdbc) {
        // 1. external_shop_id=NULL 时，同 (tenant_id, source_system, external_order_no) 重复插入被 uk_order_external_dedupe 拦截
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "external_order_no,source_system,external_shop_id,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) "
                        + "VALUES (1,'ORD-YZ-DUP',101,10,10,'YZ-EXT-1','YOUZAN',NULL,'REGULAR','PAID','PLATFORM',10000,10000,0)"))
                .isInstanceOf(DuplicateKeyException.class);

        // 2. 最大长度 48 + 1 + 96 + 1 + 128 = 274 字符不截断
        String maxSrc = "S".repeat(48);
        String maxShop = "P".repeat(96);
        String maxOrder = "O".repeat(128);
        jdbc.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "external_order_no,source_system,external_shop_id,order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) "
                + "VALUES (1,'ORD-MAX-274',101,10,10,?,?,?,'REGULAR','PAID','PLATFORM',10000,10000,0)",
                maxOrder, maxSrc, maxShop);
        assertThat(jdbc.queryForObject(
                "SELECT CHAR_LENGTH(external_order_dedupe_key) FROM crm_order WHERE order_no='ORD-MAX-274'", Integer.class))
                .isEqualTo(274);
        jdbc.update("DELETE FROM crm_order WHERE order_no='ORD-MAX-274'");

        // 3. crm_wecom_data_gap 省略 from_seq 时默认写入 0
        jdbc.update("INSERT INTO crm_wecom_data_gap (tenant_id,corp_id,archive_scope,gap_type,status,"
                + "detail_masked,first_seen_at,last_seen_at) VALUES "
                + "(1,'ww-1','ROOM','NEVER_SYNCED','OPEN','default-zero','2026-04-01 00:00:00.000','2026-04-01 00:00:00.000')");
        assertThat(jdbc.queryForObject(
                "SELECT from_seq FROM crm_wecom_data_gap WHERE archive_scope='ROOM' AND gap_type='NEVER_SYNCED'", Long.class))
                .isZero();
        jdbc.update("DELETE FROM crm_wecom_data_gap WHERE archive_scope='ROOM' AND gap_type='NEVER_SYNCED'");

        // 4. 删除 crm_youzan_event 时 ON DELETE CASCADE 自动清理 crm_youzan_order_latest
        jdbc.update("INSERT INTO crm_youzan_event (id,tenant_id,shop_id,event_id,event_type,external_order_no,"
                + "payload_masked_json,payload_cipher,source_system,process_status,received_at) VALUES "
                + "(699,1,'shop1','ev-699','ORDER_PAID','YZ-TMP-699','{}','cipher','WEBHOOK','PROCESSED','2026-04-01 06:00:00.000')");
        jdbc.update("INSERT INTO crm_youzan_order_latest (tenant_id,shop_id,external_order_no,latest_event_id) "
                + "VALUES (1,'shop1','YZ-TMP-699',699)");
        jdbc.update("DELETE FROM crm_youzan_event WHERE id=699");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM crm_youzan_order_latest WHERE external_order_no='YZ-TMP-699'", Long.class))
                .isZero();
    }

    private void assertV57RolledBackState(JdbcTemplate jdbc, String schema) {
        assertColumnExists(jdbc, schema, "crm_appointment", "confirmed_at", false);
        assertColumnExists(jdbc, schema, "crm_appointment", "checked_in_at", false);
        assertColumnExists(jdbc, schema, "crm_appointment", "service_done_at", false);
        assertTableExists(jdbc, schema, "crm_youzan_order_latest", false);
        assertColumnExists(jdbc, schema, "crm_wecom_contact", "is_unlinked", false);
        assertTableExists(jdbc, schema, "crm_material_tag", false);
        assertColumnExists(jdbc, schema, "crm_material", "latest_revision_id", false);
        assertColumnExists(jdbc, schema, "crm_order", "external_order_dedupe_key", false);

        Map<String, Object> fromSeqMeta = jdbc.queryForMap(
                "SELECT is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name='crm_wecom_data_gap' AND column_name='from_seq'",
                schema);
        assertThat(String.valueOf(fromSeqMeta.get("is_nullable"))).isEqualTo("YES");
        assertThat(fromSeqMeta.get("column_default")).isNull();

        assertForeignKeyExists(jdbc, schema, "crm_material", "fk_material_latest_revision", false);
        assertIndexColumns(jdbc, schema, "crm_material", "fk_material_latest_revision", null);

        for (String key : expectedV57Indexes().keySet()) {
            String[] parts = key.split("\\.");
            if ("crm_youzan_order_latest".equals(parts[0]) || "crm_material_tag".equals(parts[0])) {
                continue;
            }
            assertIndexColumns(jdbc, schema, parts[0], parts[1], null);
        }
    }

    private Map<String, String> expectedV57Indexes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("crm_appointment.idx_appointment_confirmed_at", "tenant_id,confirmed_at");
        map.put("crm_appointment.idx_appointment_checked_in_at", "tenant_id,checked_in_at");
        map.put("crm_appointment.idx_appointment_service_done_at", "tenant_id,service_done_at");
        map.put("crm_youzan_order_latest.uk_youzan_order_latest_1", "tenant_id,shop_id,external_order_no");
        map.put("crm_youzan_order_latest.idx_youzan_order_latest_event", "tenant_id,shop_id,latest_event_id");
        map.put("crm_youzan_order_latest.idx_youzan_order_latest_ev_id", "latest_event_id");
        map.put("crm_wecom_contact.idx_wecom_contact_unlinked", "tenant_id,is_unlinked,id");
        map.put("crm_material.idx_material_latest_revision", "tenant_id,latest_revision_id");
        map.put("crm_material_tag.uk_material_tag_1", "tenant_id,material_id,tag_code");
        map.put("crm_material_tag.idx_material_tag_reverse", "tenant_id,tag_code,material_id");
        map.put("crm_material_tag.idx_material_tag_material", "material_id");
        map.put("crm_order.uk_order_external_dedupe", "tenant_id,external_order_dedupe_key");
        map.put("crm_institution.idx_institution_archived_status_id", "tenant_id,archived,status,id");
        map.put("crm_profile_change_request.idx_profile_change_status_id", "tenant_id,status,id");
        return map;
    }

    private void seedExplainDataset(MysqlDataSource ds, JdbcTemplate jdbc) throws Exception {
        SingleConnectionDataSource singleConnDs = new SingleConnectionDataSource(ds.getConnection(), true);
        try {
            singleConnDs.getConnection().setAutoCommit(false);
            JdbcTemplate txJdbc = new JdbcTemplate(singleConnDs);
            seedExplainRows(txJdbc);
            singleConnDs.getConnection().commit();
        } finally {
            singleConnDs.destroy();
        }

        List<String> tables = List.of(
                "sys_org", "sys_team", "sys_user", "crm_customer", "crm_institution", "crm_file_object",
                "crm_appointment", "crm_youzan_event", "crm_youzan_order_latest",
                "crm_wecom_contact", "crm_material", "crm_material_revision", "crm_material_tag",
                "crm_profile_change_request");
        for (String table : tables) {
            jdbc.execute("ANALYZE TABLE `" + table + "`");
        }
    }

    private void seedExplainRows(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (1,1,'ORG_1','组织1','ACTIVE')");
        jdbc.update("INSERT INTO sys_team (id,tenant_id,org_id,team_name,status) VALUES (11,1,1,'团队11','ACTIVE')");
        jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) "
                + "VALUES (10,1,1,11,'exp_staff10','员工10','hash','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO crm_file_object (id,tenant_id,object_key,mime_type,size_bytes,sha256,classification,"
                + "scan_status,uploaded_by) VALUES "
                + "(901,1,'mat/exp/901.pdf','application/pdf',1024,'" + "c".repeat(64) + "','MATERIAL','CLEAN',10)");

        for (int i = 1; i <= 80; i++) {
            String code = String.format("%03d", i);
            long custId = 1000L + i;
            long instId = 2000L + i;
            int day = (i % 28) + 1;
            String milestoneDt = i <= 3
                    ? String.format("2026-04-01 %02d:00:00.000", 8 + i)
                    : String.format("2026-%02d-%02d 10:00:00.000", 5 + (i % 7), day);

            jdbc.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name) "
                    + "VALUES (?,1,10,11,?,?)", custId, "EXP-C-" + code, "客户" + code);

            String instStatus = i <= 50 ? "ACTIVE" : "SUSPENDED";
            int archived = i <= 60 ? 0 : 1;
            jdbc.update("INSERT INTO crm_institution (id,tenant_id,institution_no,full_name,status,settlement_type,archived) "
                    + "VALUES (?,1,?,?,?,'MONTHLY',?)",
                    instId, "EXP-INST-" + code, "机构" + code, instStatus, archived);

            long aptId = 3000L + i;
            jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                    + "scheduled_at,status,confirmed_at,checked_in_at,service_done_at,created_by) "
                    + "VALUES (?,1,?,?,?,10,'" + milestoneDt + "','SERVICE_DONE','" + milestoneDt + "','"
                    + milestoneDt + "','" + milestoneDt + "',10)",
                    aptId, "EXP-APT-" + code, custId, instId);

            long yzEvId = 4000L + i;
            String extOrderNo = "YZ-EXP-" + code;
            jdbc.update("INSERT INTO crm_youzan_event (id,tenant_id,shop_id,event_id,event_type,external_order_no,"
                    + "payload_masked_json,payload_cipher,source_system,process_status,received_at) "
                    + "VALUES (?,1,'shop-exp',?,'ORDER_PAID',?,'{}','cipher','WEBHOOK','PROCESSED','" + milestoneDt + "')",
                    yzEvId, "ev-exp-" + code, extOrderNo);
            jdbc.update("INSERT INTO crm_youzan_order_latest (tenant_id,shop_id,external_order_no,latest_event_id) "
                    + "VALUES (1,'shop-exp',?,?)", extOrderNo, yzEvId);

            jdbc.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,consent_status) "
                    + "VALUES (1,'ww-exp',?,10,?,'GRANTED')",
                    "ext-" + code, (i <= 20 ? null : custId));

            long matId = 5000L + i;
            long revId = 6000L + i;
            String tagCode = i <= 5 ? "synthetic" : String.format("tag_%02d", (i % 20) + 1);
            jdbc.update("INSERT INTO crm_material (id,tenant_id,material_no,title,category_code,review_status,created_by) "
                    + "VALUES (?,1,?,?,'POSTER','APPROVED',10)", matId, "EXP-MAT-" + code, "素材" + code);
            jdbc.update("INSERT INTO crm_material_revision (id,tenant_id,material_id,revision_no,title,category_code,"
                    + "tags_json,file_id,status,created_by) VALUES (?,?,?,1,?,'POSTER',?,901,'APPROVED',10)",
                    revId, 1L, matId, "素材" + code, "[\"" + tagCode + "\"]");
            jdbc.update("UPDATE crm_material SET latest_revision_id=?, current_revision_id=? WHERE id=?",
                    revId, revId, matId);
            jdbc.update("INSERT INTO crm_material_tag (tenant_id,material_id,tag_code) VALUES (1,?,?)",
                    matId, tagCode);

            String pcrStatus = i <= 25 ? "PENDING_REVIEW" : "APPROVED";
            jdbc.update("INSERT INTO crm_profile_change_request (tenant_id,institution_id,change_type,target_id,base_version,"
                    + "target_version,reason,status,current_step,requested_by,requested_at,active_target) "
                    + "VALUES (1,?,'PROFILE_FIELD',?,0,0,'测试变更',?,'MARKET',10,'" + milestoneDt + "',?)",
                    instId, instId, pcrStatus, (i <= 25 ? "INST:" + instId : null));
        }
    }

    private void assertUtcDateTimeEquals(Object actualValue, String expectedIsoUtc) {
        LocalDateTime expected = LocalDateTime.ofInstant(Instant.parse(expectedIsoUtc), ZoneOffset.UTC);
        if (actualValue instanceof LocalDateTime ldt) {
            assertThat(ldt).isEqualTo(expected);
            return;
        }
        if (actualValue instanceof Timestamp ts) {
            assertThat(ts.toInstant()).isEqualTo(Instant.parse(expectedIsoUtc));
            return;
        }
        assertThat(actualValue).as("Expected UTC datetime %s", expectedIsoUtc).isEqualTo(expected);
    }

    private Map<String, Object> assertExplainUsesIndex(JdbcTemplate jdbc, String sql, String expectedKey) {
        return assertExplainUsesAnyIndex(jdbc, sql, List.of(expectedKey));
    }

    private Map<String, Object> assertExplainUsesAnyIndex(JdbcTemplate jdbc, String sql, List<String> expectedKeys) {
        List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN " + sql);
        assertThat(rows).as("EXPLAIN rows for SQL: %s", sql).isNotEmpty();
        Map<String, Object> first = rows.get(0);
        String type = String.valueOf(first.get("type"));
        String key = first.get("key") == null ? null : String.valueOf(first.get("key"));
        assertThat(type).as("EXPLAIN type for SQL: %s -> %s", sql, first).isNotEqualToIgnoringCase("ALL");
        assertThat(key).as("EXPLAIN key for SQL: %s -> %s", sql, first).isIn(expectedKeys);
        return first;
    }

    private void assertTableExists(JdbcTemplate jdbc, String schema, String table, boolean expected) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?",
                Long.class, schema, table);
        assertThat(count).as("table %s exists=%s", table, expected).isEqualTo(expected ? 1L : 0L);
    }

    private void assertColumnExists(JdbcTemplate jdbc, String schema, String table, String column, boolean expected) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?",
                Long.class, schema, table, column);
        assertThat(count).as("%s.%s exists=%s", table, column, expected).isEqualTo(expected ? 1L : 0L);
    }

    private void assertForeignKeyExists(JdbcTemplate jdbc, String schema, String table, String fkName, boolean expected) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema=? AND table_name=? AND constraint_name=? AND constraint_type='FOREIGN KEY'",
                Long.class, schema, table, fkName);
        assertThat(count).as("fk %s.%s exists=%s", table, fkName, expected).isEqualTo(expected ? 1L : 0L);
    }

    private void assertIndexColumns(JdbcTemplate jdbc, String schema, String table, String indexName, String expectedCols) {
        String cols = jdbc.queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                        + "WHERE table_schema=? AND table_name=? AND index_name=?",
                String.class, schema, table, indexName);
        assertThat(cols).as("index %s.%s", table, indexName).isEqualTo(expectedCols);
    }

    private String newSchema(String prefix) throws Exception {
        String schema = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        return schema;
    }

    private void dropSchema(String schema) throws Exception {
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
