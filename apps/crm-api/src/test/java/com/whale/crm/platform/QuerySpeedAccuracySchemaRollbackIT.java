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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySpeedAccuracySchemaRollbackIT {
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
        String schema = newSchema("v56_schema_");
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            // 1. 先迁移至 V55，写入覆盖各类边界和脏数据的前置测试样本
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").target("55").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            seedPreV56Data(jdbc);
            Timestamp customer1UpdatedBefore = jdbc.queryForObject(
                    "SELECT updated_at FROM crm_customer WHERE id=101", Timestamp.class);
            Timestamp event1UpdatedBefore = jdbc.queryForObject(
                    "SELECT updated_at FROM crm_youzan_event WHERE id=601", Timestamp.class);
            Timestamp risk1UpdatedBefore = jdbc.queryForObject(
                    "SELECT updated_at FROM crm_risk_case WHERE id=901", Timestamp.class);

            // 2. 应用 V56 迁移
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();

            // 3. 核验列宽对齐、新增列、全部索引与回填准确性
            assertV56AppliedState(jdbc, schema);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM crm_customer WHERE id=101", Timestamp.class))
                    .isEqualTo(customer1UpdatedBefore);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM crm_youzan_event WHERE id=601", Timestamp.class))
                    .isEqualTo(event1UpdatedBefore);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM crm_risk_case WHERE id=901", Timestamp.class))
                    .isEqualTo(risk1UpdatedBefore);

            // 4. 验证扩宽后的 VARCHAR(192) 可正常写入超过原长度(128/96)的值（回滚前清理测试行）
            String longConvId = "conv_" + "x".repeat(175);
            String longWecomUserId = "wecom_user_" + "u".repeat(165);
            jdbc.update("INSERT INTO crm_ai_job (tenant_id,customer_id,conversation_id,requested_by,message_ids_json,"
                            + "request_hash,idempotency_key,status) VALUES (1,101,?,10,'[1]',?,'idem-v56-long','QUEUED')",
                    longConvId, "a".repeat(64));
            jdbc.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                    + "VALUES (1,'ww-v56',?,10,1)", longWecomUserId);
            jdbc.update("INSERT INTO crm_wecom_callback_event (tenant_id,corp_id,event_fingerprint,event_type,"
                            + "wecom_userid,status,received_at) VALUES (1,'ww-v56',?,'change_external_contact',?,'RECEIVED',UTC_TIMESTAMP(3))",
                    "b".repeat(64), longWecomUserId);
            assertThat(jdbc.queryForObject("SELECT CHAR_LENGTH(conversation_id) FROM crm_ai_job WHERE idempotency_key='idem-v56-long'",
                    Integer.class)).isEqualTo(180);
            jdbc.update("DELETE FROM crm_ai_job WHERE idempotency_key='idem-v56-long'");
            jdbc.update("DELETE FROM crm_wecom_staff_account WHERE corp_id='ww-v56'");
            jdbc.update("DELETE FROM crm_wecom_callback_event WHERE corp_id='ww-v56'");

            // 5. 执行 V56 回滚脚本并断言全部结构严格恢复至 V55 状态
            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V56__query_speed_accuracy_schema_rollback.sql"));
            }
            assertV56RolledBackState(jdbc, schema);

            // 6. 清除 V56 Flyway 记录后重新应用 V56，验证可重复执行且回填/索引结果完全一致
            jdbc.update("DELETE FROM flyway_schema_history WHERE version='56'");
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            assertV56AppliedState(jdbc, schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void explainVerifiesCoreHighFrequencyQueriesUseIndexes() throws Exception {
        String schema = newSchema("v56_explain_");
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            seedExplainDataset(ds, jdbc);

            // 覆盖各核心模块高频 SQL，断言走预期索引且无 ALL 全表扫描
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_customer WHERE tenant_id=1 AND deleted=0 AND team_id=11 ORDER BY id LIMIT 20",
                    "idx_customer_team");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_customer WHERE tenant_id=1 AND deleted=0 "
                            + "AND next_followup_at <= '2026-06-01 00:00:00.000' ORDER BY next_followup_at, id LIMIT 20",
                    "idx_customer_next_followup");
            assertExplainUsesIndex(jdbc,
                    "SELECT customer_id FROM crm_customer_tag WHERE tenant_id=1 AND tag_code='TAG_01'",
                    "idx_customer_tag_reverse");
            assertExplainUsesIndex(jdbc,
                    "SELECT customer_id FROM crm_customer_identity WHERE tenant_id=1 AND external_id='EXT_ID_001'",
                    "idx_customer_identity_ext");
            assertExplainUsesIndex(jdbc,
                    "SELECT customer_id FROM crm_followup WHERE tenant_id=1 AND deleted=0 "
                            + "AND contact_at >= '2026-04-01 00:00:00.000' AND contact_at < '2026-04-02 00:00:00.000'",
                    "idx_followup_contact_at");
            assertExplainUsesIndex(jdbc,
                    "SELECT appointment_id FROM crm_appointment_history WHERE tenant_id=1 AND to_status='CONFIRMED' "
                            + "AND created_at >= '2026-04-01 00:00:00.000' AND created_at < '2026-04-02 00:00:00.000'",
                    "idx_appointment_history_status_time");
            assertExplainUsesIndex(jdbc,
                    "SELECT id, customer_id, institution_id FROM crm_redemption WHERE tenant_id=1 AND status='POSTED' "
                            + "AND posted_at >= '2026-04-01 00:00:00.000' AND posted_at < '2026-04-02 00:00:00.000'",
                    "idx_redemption_status_posted");
            assertExplainUsesIndex(jdbc,
                    "SELECT redemption_id FROM crm_redemption_line WHERE tenant_id=1 AND project_id=701",
                    "idx_redemption_line_project");
            Map<String, Object> reversalPlan = assertExplainUsesIndex(jdbc,
                    "SELECT DISTINCT redemption_id FROM crm_redemption_reversal WHERE tenant_id=1 "
                            + "AND redemption_id IN (4001,4002,4003,4004,4005) AND status IN ('REQUESTED','APPROVED')",
                    "idx_redemption_reversal_redemption");
            assertThat(String.valueOf(reversalPlan.get("Extra"))).contains("Using index");

            Map<String, Object> paymentPlan = assertExplainUsesIndex(jdbc,
                    "SELECT order_id, receiver_type, amount_fen FROM crm_payment_txn WHERE tenant_id=1 AND status='CONFIRMED' "
                            + "AND confirmed_at >= '2026-04-01 00:00:00.000' AND confirmed_at < '2026-04-02 00:00:00.000'",
                    "idx_payment_txn_status_confirmed");
            assertThat(String.valueOf(paymentPlan.get("Extra"))).contains("Using index");

            assertExplainUsesIndex(jdbc,
                    "SELECT refund_id, amount_fen FROM crm_refund_payment WHERE tenant_id=1 AND status='SUCCEEDED' "
                            + "AND succeeded_at >= '2026-04-01 00:00:00.000' AND succeeded_at < '2026-04-02 00:00:00.000'",
                    "idx_refund_payment_status_succeeded");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_refund_payment WHERE provider='YOUZAN' AND external_refund_no='EXT-RF-001'",
                    "idx_refund_payment_provider_no");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_order WHERE tenant_id=1 AND status='PAID' "
                            + "AND paid_at >= '2026-04-01 00:00:00.000' AND paid_at < '2026-04-02 00:00:00.000'",
                    "idx_order_status_paid_at");
            assertExplainUsesIndex(jdbc,
                    "SELECT order_id FROM crm_order_item WHERE tenant_id=1 AND sku_id=801",
                    "idx_order_item_sku");

            Map<String, Object> ledgerPlan = assertExplainUsesIndex(jdbc,
                    "SELECT entitlement_id, COALESCE(SUM(delta_count), 0) FROM crm_entitlement_ledger "
                            + "WHERE tenant_id=1 AND entitlement_id IN (5001,5002,5003,5004,5005) GROUP BY entitlement_id",
                    "idx_entitlement_ledg_1");
            assertThat(String.valueOf(ledgerPlan.get("Extra"))).contains("Using index");

            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND corp_id='ww-exp' "
                            + "AND external_userid='ext-001' AND staff_user_id=10 AND customer_id IS NULL",
                    "idx_wecom_message_link_direct");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND conversation_id='ext-001' "
                            + "ORDER BY sent_at DESC, id DESC LIMIT 20",
                    "idx_wecom_message_conv_id");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_youzan_event WHERE tenant_id=1 AND shop_id='shop-exp' "
                            + "AND external_customer_id='yz-ext-001'",
                    "idx_youzan_event_ext_customer");
            assertExplainUsesIndex(jdbc,
                    "SELECT COUNT(DISTINCT external_order_no) FROM crm_youzan_event WHERE tenant_id=1 "
                            + "AND shop_id='shop-exp' AND campaign_id=9001",
                    "idx_youzan_event_campaign");
            assertExplainUsesIndex(jdbc,
                    "SELECT COUNT(*) FROM crm_youzan_event WHERE tenant_id=1 AND shop_id='shop-exp' "
                            + "AND received_at >= '2026-04-01 00:00:00.000' AND received_at < '2026-04-02 00:00:00.000'",
                    "idx_youzan_event_received");
            assertExplainUsesIndex(jdbc,
                    "SELECT id, order_id FROM crm_risk_case WHERE tenant_id=1 AND customer_id=1001 ORDER BY id",
                    "idx_risk_case_customer");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_task WHERE tenant_id=1 AND assignee_user_id=10 AND status='OPEN' "
                            + "AND due_at <= '2026-04-02 00:00:00.000'",
                    "idx_task_1");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_task WHERE tenant_id=1 AND customer_id=1001 AND status='OPEN'",
                    "idx_task_customer");

            Map<String, Object> notifPlan = assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_notification WHERE tenant_id=1 AND recipient_user_id=10 AND read_at IS NULL "
                            + "ORDER BY created_at DESC, id DESC LIMIT 20",
                    "idx_notification_unread");
            assertThat(String.valueOf(notifPlan.get("Extra"))).doesNotContain("Using filesort");

            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM crm_audit_log WHERE tenant_id=1 AND action_code='ORDER_PAID' ORDER BY id DESC LIMIT 20",
                    "idx_audit_log_action");
            assertExplainUsesIndex(jdbc,
                    "SELECT id FROM sys_user WHERE username='exp_user_001'",
                    "idx_user_username");
            assertExplainUsesIndex(jdbc,
                    "SELECT tenant_id FROM sys_org WHERE org_code='ORG_001' AND status='ACTIVE'",
                    "idx_org_code_status");
        } finally {
            dropSchema(schema);
        }
    }

    private void seedPreV56Data(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES "
                + "(1,1,'WHALE_T1','鲸选租户1','ACTIVE'), (2,2,'WHALE_T2','鲸选租户2','ACTIVE')");
        jdbc.update("INSERT INTO sys_team (id,tenant_id,org_id,team_name,status) VALUES "
                + "(11,1,1,'顾问一组','ACTIVE'), (21,2,2,'二租户一组','ACTIVE')");
        jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES "
                + "(10,1,1,11,'staff10','管家10','hash','EMPLOYEE','ACTIVE'),"
                + "(20,2,2,21,'staff20','管家20','hash','EMPLOYEE','ACTIVE')");

        // 客户样本（涵盖多条有效/删除跟进、无下次跟进、零跟进、跨租户隔离）
        jdbc.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name,created_at,updated_at) VALUES "
                + "(101,1,10,11,'CUST-101','客户101','2026-01-01 00:00:00.000','2026-01-02 03:04:05.000'),"
                + "(102,1,10,11,'CUST-102','客户102','2026-01-01 00:00:00.000','2026-01-02 03:04:05.000'),"
                + "(103,1,10,11,'CUST-103','客户103','2026-01-01 00:00:00.000','2026-01-02 03:04:05.000'),"
                + "(201,2,20,21,'CUST-201','客户201','2026-01-01 00:00:00.000','2026-01-02 03:04:05.000')");

        // 跟进样本
        jdbc.update("INSERT INTO crm_followup (id,tenant_id,customer_id,owner_staff_id,channel_code,contact_at,next_followup_at,created_by,deleted) VALUES "
                + "(301,1,101,10,'WECHAT','2026-01-05 08:00:00.000','2026-01-10 08:00:00.000',10,0),"
                + "(302,1,101,10,'WECHAT','2026-02-05 08:00:00.000','2026-05-20 09:30:00.000',10,0),"
                + "(303,1,101,10,'WECHAT','2026-03-05 08:00:00.000','2027-12-31 10:00:00.000',10,1),"
                + "(304,1,102,10,'WECHAT','2026-01-06 08:00:00.000',NULL,10,0),"
                + "(305,1,102,10,'WECHAT','2026-01-07 08:00:00.000','2026-08-01 00:00:00.000',10,1),"
                + "(306,2,201,20,'WECHAT','2026-02-01 08:00:00.000','2026-03-15 12:00:00.000',20,0)");

        // 订单、订单行、权益、退款、预约、核销样本（供风控多源回填验证）
        jdbc.update("INSERT INTO crm_order (id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,source_system,"
                + "order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen) VALUES "
                + "(401,1,'ORD-401',101,10,10,'CRM','REGULAR','PAID','PLATFORM',10000,10000,0),"
                + "(402,2,'ORD-402',201,20,20,'CRM','REGULAR','PAID','PLATFORM',20000,20000,0)");
        jdbc.update("INSERT INTO crm_order_item (id,tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,"
                + "purchase_quantity,list_amount_fen,paid_allocated_fen) VALUES "
                + "(411,1,401,1,1,'测试SKU',1,10000,10000)");
        jdbc.update("INSERT INTO crm_entitlement (id,tenant_id,customer_id,order_item_id,project_id,granted_count,"
                + "available_count,status) VALUES (421,1,101,411,1,2,2,'AVAILABLE')");
        jdbc.update("INSERT INTO crm_refund (id,tenant_id,refund_no,order_id,customer_id,requested_fen,approved_fen,"
                + "succeeded_fen,reason_code,status,requested_by) VALUES "
                + "(431,1,'RF-431',401,101,5000,5000,5000,'CUSTOMER_REQUEST','SUCCEEDED',10)");
        jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                + "scheduled_at,status,created_by) VALUES "
                + "(441,1,'APT-441',101,501,10,'2026-04-01 08:00:00.000','REDEEMED',10)");
        jdbc.update("INSERT INTO crm_redemption (id,tenant_id,appointment_id,customer_id,institution_id,owner_staff_id,"
                + "service_at,evidence_ref,status,idempotency_key,posted_by,posted_at) VALUES "
                + "(451,1,441,101,501,10,'2026-04-01 09:00:00.000','EV-451','POSTED','IDEM-451',10,'2026-04-01 09:05:00.000')");

        // 有赞事件样本（正常字符串/数字、JSON null、字面量 'null'/''、非法非数字/0/负数/溢出、非法 JSON、对象/超长字符串）
        String overLenExtId = "x".repeat(130);
        jdbc.update("INSERT INTO crm_youzan_event (id,tenant_id,shop_id,event_id,event_type,external_order_no,"
                + "payload_masked_json,payload_cipher,source_system,process_status,received_at,order_id,updated_at) VALUES "
                + "(601,1,'shop1','ev-601','ORDER_PAID','YZ-ORD-601','{\"external_customer_id\":\"yz-cust-001\",\"campaign_id\":8801}','cipher','WEBHOOK','PROCESSED','2026-04-01 01:00:00.000',401,'2026-04-01 01:00:00.000'),"
                + "(602,1,'shop1','ev-602','ORDER_PAID','YZ-ORD-602','{\"external_customer_id\":123456,\"campaign_id\":\"9902\"}','cipher','WEBHOOK','CUSTOMER_UNRESOLVED','2026-04-01 02:00:00.000',NULL,'2026-04-01 02:00:00.000'),"
                + "(603,1,'shop1','ev-603','ORDER_PAID','YZ-ORD-603','{\"external_customer_id\":null,\"campaign_id\":null}','cipher','WEBHOOK','RECEIVED','2026-04-01 03:00:00.000',NULL,'2026-04-01 03:00:00.000'),"
                + "(604,1,'shop1','ev-604','ORDER_PAID','YZ-ORD-604','{\"external_customer_id\":\"null\",\"campaign_id\":\"\"}','cipher','WEBHOOK','RECEIVED','2026-04-01 04:00:00.000',NULL,'2026-04-01 04:00:00.000'),"
                + "(605,1,'shop1','ev-605','ORDER_PAID','YZ-ORD-605','{\"external_customer_id\":\"   \",\"campaign_id\":\"abc\"}','cipher','WEBHOOK','RECEIVED','2026-04-01 05:00:00.000',NULL,'2026-04-01 05:00:00.000'),"
                + "(606,1,'shop1','ev-606','ORDER_PAID','YZ-ORD-606','{\"external_customer_id\":\"yz-cust-zero\",\"campaign_id\":0}','cipher','WEBHOOK','RECEIVED','2026-04-01 06:00:00.000',NULL,'2026-04-01 06:00:00.000'),"
                + "(607,1,'shop1','ev-607','ORDER_PAID','YZ-ORD-607','{\"external_customer_id\":\"yz-cust-neg\",\"campaign_id\":-7}','cipher','WEBHOOK','RECEIVED','2026-04-01 07:00:00.000',NULL,'2026-04-01 07:00:00.000'),"
                + "(608,1,'shop1','ev-608','ORDER_PAID','YZ-ORD-608','{\"external_customer_id\":\"yz-cust-ovf\",\"campaign_id\":\"99999999999999999999999999\"}','cipher','WEBHOOK','RECEIVED','2026-04-01 08:00:00.000',NULL,'2026-04-01 08:00:00.000'),"
                + "(609,1,'shop1','ev-609','ORDER_PAID','YZ-ORD-609','{broken-json','cipher','WEBHOOK','RECEIVED','2026-04-01 09:00:00.000',NULL,'2026-04-01 09:00:00.000'),"
                + "(610,1,'shop1','ev-610','ORDER_PAID','YZ-ORD-610','{\"external_customer_id\":{\"nested\":1},\"campaign_id\":[1,2]}','cipher','WEBHOOK','RECEIVED','2026-04-01 10:00:00.000',NULL,'2026-04-01 10:00:00.000'),"
                + "(611,1,'shop1','ev-611','ORDER_PAID','YZ-ORD-611','{\"external_customer_id\":\"" + overLenExtId + "\",\"campaign_id\":\"18446744073709551615\"}','cipher','WEBHOOK','RECEIVED','2026-04-01 11:00:00.000',NULL,'2026-04-01 11:00:00.000')");

        jdbc.update("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,customer_id,link_type,confidence,created_by) "
                + "VALUES (1,'shop1','123456',102,'MANUAL','HIGH',10)");
        jdbc.update("INSERT INTO crm_youzan_sync_issue (id,tenant_id,event_id,issue_type,status) VALUES "
                + "(701,1,601,'PAYMENT_MISMATCH','OPEN'),"
                + "(702,1,602,'MAPPING_FAILED','OPEN')");

        // 风控样本（覆盖 5 种合法来源 + 非数字/溢出/其他类型/跨租户隔离）
        jdbc.update("INSERT INTO crm_risk_case (id,tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,status,updated_at) VALUES "
                + "(901,1,'RC-901','ORDER_PAYMENT_CASH','HIGH','ORDER','401','OPEN','2026-04-01 12:00:00.000'),"
                + "(902,1,'RC-902','PAYMENT_MISMATCH','HIGH','YOUZAN_SYNC_ISSUE','701','OPEN','2026-04-01 12:00:00.000'),"
                + "(903,1,'RC-903','MAPPING_FAILED','MEDIUM','YOUZAN_SYNC_ISSUE','702','OPEN','2026-04-01 12:00:00.000'),"
                + "(904,1,'RC-904','REFUND_ALLOCATION','HIGH','REFUND','431','OPEN','2026-04-01 12:00:00.000'),"
                + "(905,1,'RC-905','ENTITLEMENT_LEDGER','HIGH','ENTITLEMENT','421','OPEN','2026-04-01 12:00:00.000'),"
                + "(906,1,'RC-906','UNBATCHED_REDEMPTION','MEDIUM','REDEMPTION','451','OPEN','2026-04-01 12:00:00.000'),"
                + "(907,1,'RC-907','ORDER_PAYMENT_CASH','HIGH','ORDER','NON-NUMERIC-REF','OPEN','2026-04-01 12:00:00.000'),"
                + "(908,1,'RC-908','ORDER_PAYMENT_CASH','HIGH','ORDER','99999999999999999999999999','OPEN','2026-04-01 12:00:00.000'),"
                + "(909,1,'RC-909','SETTLEMENT_PAYMENT','HIGH','SETTLEMENT_BATCH','123','OPEN','2026-04-01 12:00:00.000'),"
                + "(910,1,'RC-910','ORDER_PAYMENT_CASH','HIGH','ORDER','402','OPEN','2026-04-01 12:00:00.000')");
    }

    private void assertV56AppliedState(JdbcTemplate jdbc, String schema) {
        // A. 列宽对齐验证
        assertColumnMaxLength(jdbc, schema, "crm_ai_job", "conversation_id", 192L, "NO");
        assertColumnMaxLength(jdbc, schema, "crm_ai_chat_delegation_run", "conversation_id", 192L, "YES");
        assertColumnMaxLength(jdbc, schema, "crm_wecom_staff_account", "wecom_userid", 192L, "NO");
        assertColumnMaxLength(jdbc, schema, "crm_wecom_callback_event", "wecom_userid", 192L, "YES");

        // B. 新增列存在性验证
        assertColumnExists(jdbc, schema, "crm_customer", "next_followup_at", true);
        assertColumnExists(jdbc, schema, "crm_youzan_event", "external_customer_id", true);
        assertColumnExists(jdbc, schema, "crm_youzan_event", "campaign_id", true);
        assertColumnExists(jdbc, schema, "crm_risk_case", "customer_id", true);
        assertColumnExists(jdbc, schema, "crm_risk_case", "order_id", true);

        // B1. crm_customer.next_followup_at 回填验证
        assertThat(jdbc.queryForObject("SELECT next_followup_at FROM crm_customer WHERE id=101", Timestamp.class))
                .isEqualTo(Timestamp.from(Instant.parse("2026-05-20T09:30:00Z")));
        assertThat(jdbc.queryForObject("SELECT next_followup_at FROM crm_customer WHERE id=102", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT next_followup_at FROM crm_customer WHERE id=103", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT next_followup_at FROM crm_customer WHERE id=201", Timestamp.class))
                .isEqualTo(Timestamp.from(Instant.parse("2026-03-15T12:00:00Z")));

        // B2. crm_youzan_event 回填验证
        assertYouzanEventBackfill(jdbc, 601L, "yz-cust-001", "8801");
        assertYouzanEventBackfill(jdbc, 602L, "123456", "9902");
        assertYouzanEventBackfill(jdbc, 603L, null, null);
        assertYouzanEventBackfill(jdbc, 604L, null, null);
        assertYouzanEventBackfill(jdbc, 605L, null, null);
        assertYouzanEventBackfill(jdbc, 606L, "yz-cust-zero", null);
        assertYouzanEventBackfill(jdbc, 607L, "yz-cust-neg", null);
        assertYouzanEventBackfill(jdbc, 608L, "yz-cust-ovf", null);
        assertYouzanEventBackfill(jdbc, 609L, null, null);
        assertYouzanEventBackfill(jdbc, 610L, null, null);
        assertYouzanEventBackfill(jdbc, 611L, null, "18446744073709551615");

        // B3. crm_risk_case 多源安全回填验证
        assertRiskCaseBackfill(jdbc, 901L, 101L, 401L);
        assertRiskCaseBackfill(jdbc, 902L, 101L, 401L);
        assertRiskCaseBackfill(jdbc, 903L, 102L, null);
        assertRiskCaseBackfill(jdbc, 904L, 101L, 401L);
        assertRiskCaseBackfill(jdbc, 905L, 101L, 401L);
        assertRiskCaseBackfill(jdbc, 906L, 101L, null);
        assertRiskCaseBackfill(jdbc, 907L, null, null);
        assertRiskCaseBackfill(jdbc, 908L, null, null);
        assertRiskCaseBackfill(jdbc, 909L, null, null);
        assertRiskCaseBackfill(jdbc, 910L, null, null);

        // C. 全部新增/调整索引列序验证
        for (Map.Entry<String, String> entry : expectedV56Indexes().entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            assertIndexColumns(jdbc, schema, parts[0], parts[1], entry.getValue());
        }

        // C. 2 个重复索引已删除验证
        assertIndexColumns(jdbc, schema, "crm_followup_batch_order", "idx_followup_batch_order_item", null);
        assertIndexColumns(jdbc, schema, "crm_merge_resource_snapshot", "idx_merge_resource_snapshot_1", null);
    }

    private void assertV56RolledBackState(JdbcTemplate jdbc, String schema) {
        // A. 列宽恢复
        assertColumnMaxLength(jdbc, schema, "crm_ai_job", "conversation_id", 128L, "NO");
        assertColumnMaxLength(jdbc, schema, "crm_ai_chat_delegation_run", "conversation_id", 128L, "YES");
        assertColumnMaxLength(jdbc, schema, "crm_wecom_staff_account", "wecom_userid", 96L, "NO");
        assertColumnMaxLength(jdbc, schema, "crm_wecom_callback_event", "wecom_userid", 96L, "YES");

        // B. 新增列全部移除
        assertColumnExists(jdbc, schema, "crm_customer", "next_followup_at", false);
        assertColumnExists(jdbc, schema, "crm_youzan_event", "external_customer_id", false);
        assertColumnExists(jdbc, schema, "crm_youzan_event", "campaign_id", false);
        assertColumnExists(jdbc, schema, "crm_risk_case", "customer_id", false);
        assertColumnExists(jdbc, schema, "crm_risk_case", "order_id", false);

        // C. 新增索引全部移除
        Map<String, String> replacedOriginals = Map.of(
                "crm_payment_txn.idx_payment_txn_1", "tenant_id,order_id,status",
                "crm_entitlement_ledger.idx_entitlement_ledg_1", "tenant_id,entitlement_id",
                "crm_task.idx_task_1", "tenant_id,assignee_user_id,due_at,status",
                "crm_notification.idx_notification_unread", "tenant_id,recipient_user_id,read_at,id");

        for (String key : expectedV56Indexes().keySet()) {
            String[] parts = key.split("\\.");
            String expectedAfterRollback = replacedOriginals.get(key);
            assertIndexColumns(jdbc, schema, parts[0], parts[1], expectedAfterRollback);
        }

        // C. 2 个原重复索引恢复
        assertIndexColumns(jdbc, schema, "crm_followup_batch_order", "idx_followup_batch_order_item",
                "tenant_id,item_id,order_id");
        assertIndexColumns(jdbc, schema, "crm_merge_resource_snapshot", "idx_merge_resource_snapshot_1",
                "tenant_id,merge_case_id,resource_type");
    }

    private Map<String, String> expectedV56Indexes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("crm_customer.idx_customer_next_followup", "tenant_id,deleted,next_followup_at,id");
        map.put("crm_customer.idx_customer_team", "tenant_id,deleted,team_id,id");
        map.put("crm_customer.idx_customer_created", "tenant_id,deleted,created_at");
        map.put("crm_customer.idx_customer_status", "tenant_id,deleted,customer_status,id");
        map.put("crm_customer.idx_customer_city_tier", "tenant_id,deleted,city_code,tier_code,id");
        map.put("crm_youzan_event.idx_youzan_event_ext_customer", "tenant_id,shop_id,external_customer_id");
        map.put("crm_youzan_event.idx_youzan_event_campaign", "tenant_id,shop_id,campaign_id,external_order_no");
        map.put("crm_youzan_event.idx_youzan_event_received", "tenant_id,shop_id,received_at");
        map.put("crm_risk_case.idx_risk_case_customer", "tenant_id,customer_id,id");
        map.put("crm_risk_case.idx_risk_case_created", "tenant_id,created_at,id");
        map.put("crm_customer_tag.idx_customer_tag_reverse", "tenant_id,tag_code,customer_id");
        map.put("crm_customer_identity.idx_customer_identity_ext", "tenant_id,external_id,customer_id");
        map.put("crm_followup.idx_followup_contact_at", "tenant_id,deleted,contact_at,customer_id");
        map.put("crm_followup.idx_followup_created_by", "tenant_id,deleted,created_by,id");
        map.put("crm_appointment_history.idx_appointment_history_status_time", "tenant_id,to_status,created_at,appointment_id");
        map.put("crm_redemption.idx_redemption_status_posted", "tenant_id,status,posted_at,customer_id,institution_id");
        map.put("crm_redemption.idx_redemption_status_id", "tenant_id,status,id");
        map.put("crm_redemption_line.idx_redemption_line_project", "tenant_id,project_id,redemption_id");
        map.put("crm_redemption_reversal.idx_redemption_reversal_redemption", "tenant_id,redemption_id,status");
        map.put("crm_payment_txn.idx_payment_txn_status_confirmed", "tenant_id,status,confirmed_at,order_id,receiver_type,amount_fen");
        map.put("crm_payment_txn.idx_payment_txn_1", "tenant_id,order_id,status,amount_fen");
        map.put("crm_refund_payment.idx_refund_payment_status_succeeded", "tenant_id,status,succeeded_at,refund_id,amount_fen");
        map.put("crm_refund_payment.idx_refund_payment_provider_no", "provider,external_refund_no");
        map.put("crm_refund.idx_refund_status_id", "tenant_id,status,id");
        map.put("crm_order.idx_order_status_paid_at", "tenant_id,status,paid_at");
        map.put("crm_order.idx_order_owner_sale_paid", "tenant_id,owner_at_sale_id,paid_at");
        map.put("crm_order.idx_order_ordered_at", "tenant_id,ordered_at,status,customer_id");
        map.put("crm_order.idx_order_status_id", "tenant_id,status,id");
        map.put("crm_order_item.idx_order_item_sku", "tenant_id,sku_id,order_id");
        map.put("crm_entitlement_ledger.idx_entitlement_ledg_1", "tenant_id,entitlement_id,delta_count,txn_type");
        map.put("crm_wecom_message.idx_wecom_message_sent_id", "tenant_id,sent_at,id");
        map.put("crm_wecom_message.idx_wecom_message_conv_id", "tenant_id,conversation_id,sent_at,id");
        map.put("crm_wecom_message.idx_wecom_message_link_direct", "tenant_id,corp_id,external_userid,staff_user_id,customer_id");
        map.put("crm_wecom_contact.idx_wecom_contact_consent_cover", "tenant_id,corp_id,external_userid,consent_status,staff_user_id");
        map.put("crm_wecom_staff_account.idx_wecom_staff_user_msg", "tenant_id,staff_user_id,message_enabled,corp_id");
        map.put("crm_wecom_callback_event.idx_wecom_callback_pending", "tenant_id,status,next_retry_at,id");
        map.put("crm_task.idx_task_1", "tenant_id,assignee_user_id,status,due_at");
        map.put("crm_task.idx_task_customer", "tenant_id,customer_id,status,id");
        map.put("crm_task.idx_task_due_poll", "status,source_type,due_at,tenant_id");
        map.put("crm_notification.idx_notification_unread", "tenant_id,recipient_user_id,read_at,created_at,id");
        map.put("crm_notification_delivery.idx_notification_delivery_poll", "status,next_attempt_at,tenant_id");
        map.put("crm_followup_batch.idx_followup_batch_poll", "status,next_retry_at,tenant_id");
        map.put("crm_quick_reply_usage.idx_quick_reply_usage_created", "tenant_id,created_at,employee_id,send_status");
        map.put("crm_quick_reply_usage.idx_quick_reply_usage_send_time", "tenant_id,send_time,employee_id,template_id");
        map.put("crm_ai_reply_suggestion.idx_ai_reply_created", "tenant_id,created_at,employee_id,status");
        map.put("crm_ai_chat_delegation_run.idx_ai_delegation_customer_status", "tenant_id,customer_id,status,id");
        map.put("crm_audit_log.idx_audit_log_action", "tenant_id,action_code,id");
        map.put("crm_audit_log.idx_audit_log_tenant_id", "tenant_id,id");
        map.put("crm_merge_case.idx_merge_case_secondary", "tenant_id,secondary_customer_id,status,id");
        map.put("sys_user.idx_user_username", "username");
        map.put("sys_org.idx_org_code_status", "org_code,status,tenant_id");
        return map;
    }

    private void seedExplainDataset(MysqlDataSource ds, JdbcTemplate jdbc) throws Exception {
        // 使用单事务批量写入 80 组区分度样本并 ANALYZE TABLE，兼顾速度与真实优化器统计信息
        org.springframework.jdbc.datasource.SingleConnectionDataSource singleConnDs =
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(ds.getConnection(), true);
        try {
            singleConnDs.getConnection().setAutoCommit(false);
            JdbcTemplate txJdbc = new JdbcTemplate(singleConnDs);
            seedExplainRows(txJdbc);
            singleConnDs.getConnection().commit();
        } finally {
            singleConnDs.destroy();
        }

        List<String> tables = List.of(
                "sys_org", "sys_user", "crm_customer", "crm_customer_tag", "crm_customer_identity",
                "crm_followup", "crm_appointment_history", "crm_redemption", "crm_redemption_line",
                "crm_redemption_reversal", "crm_payment_txn", "crm_refund_payment", "crm_order",
                "crm_order_item", "crm_entitlement_ledger", "crm_wecom_message", "crm_youzan_event",
                "crm_risk_case", "crm_task", "crm_notification", "crm_audit_log");
        for (String table : tables) {
            jdbc.execute("ANALYZE TABLE `" + table + "`");
        }
    }

    private void seedExplainRows(JdbcTemplate jdbc) {
        for (int i = 1; i <= 80; i++) {
            String code = String.format("%03d", i);
            long tenant = i <= 60 ? 1L : 2L;
            jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (?,?,?,?,'ACTIVE')",
                    i, tenant, "ORG_" + code, "组织" + code);
            jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,username,display_name,password_hash,user_type,status) "
                    + "VALUES (?,?,?,?,?,'hash','EMPLOYEE','ACTIVE')",
                    i, tenant, i, "exp_user_" + code, "员工" + code);
        }

        for (int i = 1; i <= 80; i++) {
            String code = String.format("%03d", i);
            long custId = 1000L + i;
            long teamId = 10L + (i % 10) + 1;
            int day = (i % 28) + 1;
            String dt = String.format("2026-%02d-%02d 08:00:00.000", (i <= 3 ? 4 : 5 + (i % 6)), day);
            String followDt = i == 1 ? "2026-04-01 10:00:00.000" : String.format("2026-%02d-%02d 10:00:00.000", 7 + (i % 5), day);
            jdbc.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name,next_followup_at,deleted) "
                    + "VALUES (?,1,10,?,?,?,'" + followDt + "',0)",
                    custId, teamId, "EXP-C-" + code, "客户" + code);
            jdbc.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code) VALUES (1,?,?)",
                    custId, String.format("TAG_%02d", (i % 20) + 1));
            jdbc.update("INSERT INTO crm_customer_identity (tenant_id,customer_id,identity_type,source_app,external_id,status) "
                    + "VALUES (1,?,'WECOM','ww-exp',?,'LINKED')",
                    custId, "EXT_ID_" + code);
            jdbc.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,contact_at,created_by,deleted) "
                    + "VALUES (1,?,10,'WECHAT','" + dt + "',10,0)", custId);

            long orderId = 2000L + i;
            String orderStatus = i <= 40 ? "PAID" : "CANCELED";
            String paidAt = i == 1 ? "2026-04-01 12:00:00.000" : String.format("2026-%02d-%02d 12:00:00.000", 5 + (i % 7), day);
            jdbc.update("INSERT INTO crm_order (id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,source_system,"
                    + "order_type_code,status,receiver_type,payable_fen,paid_fen,refunded_fen,ordered_at,paid_at) "
                    + "VALUES (?,1,?,?,10,10,'CRM','REGULAR',?,'PLATFORM',10000,10000,0,'" + paidAt + "','" + paidAt + "')",
                    orderId, "EXP-O-" + code, custId, orderStatus);

            long orderItemId = 3000L + i;
            jdbc.update("INSERT INTO crm_order_item (id,tenant_id,order_id,sku_id,package_version_id,sku_name_snapshot,"
                    + "purchase_quantity,list_amount_fen,paid_allocated_fen) VALUES (?,1,?,?,1,'SKU',1,10000,10000)",
                    orderItemId, orderId, 800L + (i % 25) + 1);

            long entId = 5000L + i;
            jdbc.update("INSERT INTO crm_entitlement (id,tenant_id,customer_id,order_item_id,project_id,granted_count,"
                    + "available_count,status) VALUES (?,1,?,?,?,2,2,'AVAILABLE')",
                    entId, custId, orderItemId, 700L + (i % 20) + 1);
            jdbc.update("INSERT INTO crm_entitlement_ledger (tenant_id,entitlement_id,txn_type,delta_count,source_type,"
                    + "source_ref,idempotency_key) VALUES (1,?,'GRANT',2,'ORDER',?,?)",
                    entId, String.valueOf(orderId), "LED-IDEM-" + code);
            if (i <= 5) {
                jdbc.update("INSERT INTO crm_entitlement_ledger (tenant_id,entitlement_id,txn_type,delta_count,source_type,"
                        + "source_ref,idempotency_key) VALUES (1,?,'REDEEM',-1,'REDEMPTION',?,?)",
                        entId, String.valueOf(4000L + i), "LED-RED-" + code);
            }

            jdbc.update("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,external_trade_no,"
                    + "amount_fen,status,confirmed_at) VALUES (1,?,'BANK','PLATFORM',?,10000,?,'" + paidAt + "')",
                    orderId, "PAY-" + code, i <= 40 ? "CONFIRMED" : "PENDING");

            long refundId = 6000L + i;
            jdbc.update("INSERT INTO crm_refund (id,tenant_id,refund_no,order_id,customer_id,requested_fen,approved_fen,"
                    + "succeeded_fen,reason_code,status,requested_by) VALUES (?,1,?,?,?,1000,1000,1000,'REASON',?,10)",
                    refundId, "EXP-RF-" + code, orderId, custId, i <= 30 ? "SUCCEEDED" : "REQUESTED");
            jdbc.update("INSERT INTO crm_refund_payment (tenant_id,refund_id,provider,external_refund_no,amount_fen,"
                    + "status,verification_method,succeeded_at) VALUES (1,?,'YOUZAN',?,1000,?,'MANUAL_REVIEW','" + paidAt + "')",
                    refundId, "EXT-RF-" + code, i <= 30 ? "SUCCEEDED" : "PENDING");

            long aptId = 7000L + i;
            jdbc.update("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,owner_staff_id,"
                    + "scheduled_at,status,created_by) VALUES (?,1,?,?,501,10,'" + paidAt + "','REDEEMED',10)",
                    aptId, "EXP-APT-" + code, custId);
            jdbc.update("INSERT INTO crm_appointment_history (tenant_id,appointment_id,from_status,to_status,actor_user_id,created_at) "
                    + "VALUES (1,?,'PENDING_INSTITUTION',?,10,'" + paidAt + "')",
                    aptId, i <= 30 ? "CONFIRMED" : "CHECKED_IN");
            long aptItemId = 7500L + i;
            jdbc.update("INSERT INTO crm_appointment_item (id,tenant_id,appointment_id,entitlement_id,project_id,planned_count) "
                    + "VALUES (?,1,?,?,?,1)", aptItemId, aptId, entId, 700L + (i % 20) + 1);

            long redId = 4000L + i;
            jdbc.update("INSERT INTO crm_redemption (id,tenant_id,appointment_id,customer_id,institution_id,owner_staff_id,"
                    + "service_at,evidence_ref,status,idempotency_key,posted_by,posted_at) "
                    + "VALUES (?,1,?,?,501,10,'" + paidAt + "',?,'POSTED',?,10,'" + paidAt + "')",
                    redId, aptId, custId, "EV-" + code, "RED-IDEM-" + code);
            jdbc.update("INSERT INTO crm_redemption_line (tenant_id,redemption_id,appointment_item_id,entitlement_id,project_id,count) "
                    + "VALUES (1,?,?,?,?,1)", redId, aptItemId, entId, 700L + (i % 20) + 1);
            jdbc.update("INSERT INTO crm_redemption_reversal (tenant_id,redemption_id,customer_id,institution_id,owner_staff_id,"
                    + "status,reason,requested_by,requested_at,request_idempotency_key) "
                    + "VALUES (1,?,?,501,10,?,'测试冲正',10,'" + paidAt + "',?)",
                    redId, custId, i <= 20 ? "APPROVED" : "REJECTED", "REV-IDEM-" + code);

            jdbc.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,external_userid,"
                    + "staff_user_id,conversation_type,conversation_id,message_type,sender_type,sent_at,content_object_key,"
                    + "content_sha256,consent_snapshot,status) "
                    + "VALUES (1,'ww-exp',?,?,NULL,?,10,'DIRECT',?,'TEXT','CUSTOMER','" + paidAt + "',?,?,'GRANTED','UNMATCHED')",
                    "msg-" + code, i, "ext-" + code, "ext-" + code, "obj-" + code, "c".repeat(64));

            jdbc.update("INSERT INTO crm_youzan_event (tenant_id,shop_id,event_id,event_type,external_order_no,"
                    + "external_customer_id,campaign_id,payload_masked_json,payload_cipher,source_system,process_status,received_at) "
                    + "VALUES (1,'shop-exp',?,'ORDER_PAID',?,?,?,'{}','cipher','WEBHOOK','PROCESSED','" + paidAt + "')",
                    "yz-ev-" + code, "YZ-E-" + code, "yz-ext-" + code, 9000L + (i % 20) + 1);

            jdbc.update("INSERT INTO crm_risk_case (tenant_id,risk_case_no,risk_type,severity,source_type,source_ref,"
                    + "customer_id,order_id,status) VALUES (1,?,'ORDER_PAYMENT_CASH','HIGH','ORDER',?,?,?,'OPEN')",
                    "EXP-RC-" + code, String.valueOf(orderId), custId, orderId);

            jdbc.update("INSERT INTO crm_task (tenant_id,customer_id,assignee_user_id,task_type,priority,status,title,"
                    + "due_at,source_type,source_ref) VALUES (1,?,?, 'FOLLOWUP','HIGH',?,'任务','" + paidAt + "','MANUAL',?)",
                    custId, (i <= 20 ? 10L : 10L + (i % 15)), (i % 4 == 1 ? "OPEN" : "DONE"), "TSK-" + code);

            jdbc.update("INSERT INTO crm_notification (tenant_id,recipient_user_id,notification_type,title,content_masked,"
                    + "source_type,source_id,dedupe_key,channels,created_at,read_at) VALUES "
                    + "(1,?,'TASK_DUE','标题','内容','TASK',?,?,'IN_APP','" + paidAt + "',"
                    + (i % 5 == 1 ? "NULL" : "'" + paidAt + "'") + ")",
                    (i <= 30 ? 10L : 10L + (i % 20)), code, "DEDUPE-" + code);

            jdbc.update("INSERT INTO crm_audit_log (tenant_id,actor_user_id,action_code,target_type,target_id) "
                    + "VALUES (1,10,?,'ORDER',?)", (i <= 10 ? "ORDER_PAID" : "ACT_" + (i % 15)), String.valueOf(orderId));
        }
    }

    private Map<String, Object> assertExplainUsesIndex(JdbcTemplate jdbc, String sql, String expectedKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("EXPLAIN " + sql);
        assertThat(rows).as("EXPLAIN rows for SQL: %s", sql).isNotEmpty();
        Map<String, Object> first = rows.get(0);
        String type = String.valueOf(first.get("type"));
        String key = first.get("key") == null ? null : String.valueOf(first.get("key"));
        assertThat(type).as("EXPLAIN type for SQL: %s -> %s", sql, first).isNotEqualToIgnoringCase("ALL");
        assertThat(key).as("EXPLAIN key for SQL: %s -> %s", sql, first).isEqualTo(expectedKey);
        return first;
    }

    private void assertYouzanEventBackfill(JdbcTemplate jdbc, long id, String expectedExtCustomerId, String expectedCampaignId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT external_customer_id, CAST(campaign_id AS CHAR) AS campaign_id_str FROM crm_youzan_event WHERE id=?", id);
        assertThat(row.get("external_customer_id")).as("event %d external_customer_id", id).isEqualTo(expectedExtCustomerId);
        assertThat(row.get("campaign_id_str")).as("event %d campaign_id", id).isEqualTo(expectedCampaignId);
    }

    private void assertRiskCaseBackfill(JdbcTemplate jdbc, long id, Long expectedCustomerId, Long expectedOrderId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT customer_id, order_id FROM crm_risk_case WHERE id=?", id);
        Long actualCustomerId = row.get("customer_id") == null ? null : ((Number) row.get("customer_id")).longValue();
        Long actualOrderId = row.get("order_id") == null ? null : ((Number) row.get("order_id")).longValue();
        assertThat(actualCustomerId).as("risk_case %d customer_id", id).isEqualTo(expectedCustomerId);
        assertThat(actualOrderId).as("risk_case %d order_id", id).isEqualTo(expectedOrderId);
    }

    private void assertColumnMaxLength(JdbcTemplate jdbc, String schema, String table, String column,
                                       long expectedMaxLen, String expectedNullable) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT character_maximum_length, is_nullable FROM information_schema.columns "
                        + "WHERE table_schema=? AND table_name=? AND column_name=?",
                schema, table, column);
        assertThat(((Number) row.get("character_maximum_length")).longValue())
                .as("%s.%s max_length", table, column).isEqualTo(expectedMaxLen);
        assertThat(String.valueOf(row.get("is_nullable")))
                .as("%s.%s is_nullable", table, column).isEqualTo(expectedNullable);
    }

    private void assertColumnExists(JdbcTemplate jdbc, String schema, String table, String column, boolean expected) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?",
                Long.class, schema, table, column);
        assertThat(count).as("%s.%s exists=%s", table, column, expected).isEqualTo(expected ? 1L : 0L);
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
