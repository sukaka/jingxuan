package com.whale.crm.platform;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.whale.crm.complaint.domain.ComplaintTimelineQuery;
import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.exporting.domain.ExportDataQuery;
import com.whale.crm.exporting.domain.ExportFilter;
import com.whale.crm.exporting.domain.ExportRestriction;
import com.whale.crm.exporting.domain.ExportScope;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.fulfillment.domain.FulfillmentScope;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import com.whale.crm.reporting.domain.ReportCriteria;
import com.whale.crm.reporting.domain.ReportQuery;
import com.whale.crm.reporting.domain.ReportScope;
import com.whale.crm.reporting.domain.ReportWindow;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Wave1Wave2ExplainIT {

    private static final Logger LOG = LoggerFactory.getLogger(Wave1Wave2ExplainIT.class);
    private static final int SEED_ROWS = 1000;

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withCommand("--log-bin-trust-function-creators=1")
            .withTmpFs(Map.of("/var/lib/mysql", "rw,mode=1777"))
            .withEnv("MYSQL_INITDB_SKIP_TZINFO", "1")
            .withStartupTimeoutSeconds(600);

    private static String schema;
    private static MysqlDataSource dataSource;
    private static Configuration mybatisConfig;

    @BeforeAll
    static void startAndSeed() throws Exception {
        MYSQL.start();
        schema = "w1w2_explain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        dataSource = dataSourceFor(schema);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedDataset(dataSource, jdbc);
        mybatisConfig = loadMappers();
    }

    @AfterAll
    static void stopContainer() {
        try {
            if (schema != null) {
                MysqlDataSource rootDs = dataSourceFor("medical_crm");
                try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
                }
            }
        } catch (Exception ex) {
            LOG.warn("Failed to drop temporary schema {}: {}", schema, ex.getMessage());
        } finally {
            MYSQL.stop();
        }
    }

    @Test
    void explainVerifiesWave1LiveMapperSqls() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 1. CustomerMapper#search & countSearch (TEAM scope, selfId=10, teamIds=[11])
            Map<String, Object> custSearchParams = customerSearchParams("TEAM", List.of(11L), 10L, null);
            List<Map<String, Object>> custSearchRows = assertNoFullTableScans(
                    conn, "CustomerMapper.search(TEAM)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.search",
                    custSearchParams);
            Map<String, Object> custRow = rowForTable(custSearchRows, "c");
            assertThat(String.valueOf(custRow.get("key")))
                    .containsAnyOf("PRIMARY", "idx_customer_team", "idx_customer_1");
            assertThat(rowForTable(custSearchRows, "o").get("key")).isEqualTo("PRIMARY");

            Map<String, Object> custSelfParams = customerSearchParams("SELF", List.of(), 10L, null);
            List<Map<String, Object>> custCountRows = assertNoFullTableScans(
                    conn, "CustomerMapper.countSearch(SELF)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.countSearch",
                    custSelfParams);
            assertThat(rowForTable(custCountRows, "c").get("key")).isEqualTo("idx_customer_1");

            // 2. CustomerTagMapper#selectTagCodesByCustomerIds
            List<Map<String, Object>> tagRows = assertNoFullTableScans(
                    conn, "CustomerTagMapper.selectTagCodesByCustomerIds",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerTagMapper.selectTagCodesByCustomerIds",
                    params("tenantId", 1L, "customerIds", List.of(1001L, 1002L, 1003L)));
            Map<String, Object> tagRow = tagRows.get(0);
            assertThat(tagRow.get("key")).isEqualTo("uk_customer_tag_1");
            assertThat(String.valueOf(tagRow.get("Extra")))
                    .contains("Using index")
                    .doesNotContain("Using filesort");

            // 3. CustomerMapper#selectCustomerIdsByExternalId
            List<Map<String, Object>> extIdRows = assertNoFullTableScans(
                    conn, "CustomerMapper.selectCustomerIdsByExternalId",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.selectCustomerIdsByExternalId",
                    params("tenantId", 1L, "scopeType", "ALL", "teamIds", List.of(),
                            "selfId", 10L, "externalId", "EXT_ID_0001"));
            Map<String, Object> identityRow = rowForTable(extIdRows, "i");
            assertThat(identityRow.get("key")).isEqualTo("idx_customer_identity_ext");
            assertThat(String.valueOf(identityRow.get("Extra"))).contains("Using index");
            assertThat(rowForTable(extIdRows, "c").get("key")).isEqualTo("PRIMARY");

            // 4. ReportingMapper#operations + countNewCustomers + performance + campaigns
            ReportQuery reportQuery = new ReportQuery(
                    1L,
                    ReportWindow.of(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1), "UTC"),
                    new ReportScope("ALL", List.of(), 10L),
                    new ReportCriteria(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1),
                            "UTC", "ALL", "ALL", "P2_FACT_V1", null, null, null, null, null, null, null),
                    0, 20);
            List<Map<String, Object>> opsRows = assertNoFullTableScans(
                    conn, "ReportingMapper.operations",
                    "com.whale.crm.reporting.infrastructure.mapper.ReportingMapper.operations",
                    params("query", reportQuery));
            List<String> aptKeys = opsRows.stream()
                    .filter(r -> "a".equals(r.get("table")))
                    .map(r -> String.valueOf(r.get("key")))
                    .toList();
            assertThat(aptKeys).containsExactlyInAnyOrder(
                    "idx_appointment_confirmed_at",
                    "idx_appointment_checked_in_at",
                    "idx_appointment_service_done_at");
            assertThat(opsRows.stream().map(r -> String.valueOf(r.get("table"))))
                    .noneMatch(t -> t.contains("appointment_history"));

            List<Map<String, Object>> newCustRows = assertNoFullTableScans(
                    conn, "ReportingMapper.countNewCustomers",
                    "com.whale.crm.reporting.infrastructure.mapper.ReportingMapper.countNewCustomers",
                    params("query", reportQuery));
            assertThat(rowForTable(newCustRows, "c").get("key")).isEqualTo("idx_customer_created");

            assertNoFullTableScans(
                    conn, "ReportingMapper.performance(MANAGER)",
                    "com.whale.crm.reporting.infrastructure.mapper.ReportingMapper.performance",
                    params("query", reportQuery, "performanceRole", "MANAGER"));

            List<Map<String, Object>> campaignRows = assertNoFullTableScans(
                    conn, "ReportingMapper.campaigns",
                    "com.whale.crm.reporting.infrastructure.mapper.ReportingMapper.campaigns",
                    params("query", reportQuery));
            List<String> campAptKeys = campaignRows.stream()
                    .filter(r -> "a".equals(r.get("table")))
                    .map(r -> String.valueOf(r.get("key")))
                    .toList();
            assertThat(campAptKeys).containsExactlyInAnyOrder(
                    "idx_appointment_confirmed_at",
                    "idx_appointment_checked_in_at");
            assertThat(campaignRows.stream().map(r -> String.valueOf(r.get("table"))))
                    .noneMatch(t -> t.contains("appointment_history"));

            // 5. WecomMapper#selectMessages (unfiltered ALL + conversationId filtered + TEAM)
            Map<String, Object> msgAllParams = params(
                    "tenantId", 1L, "customerId", null, "conversationId", null, "corpId", null,
                    "scopeType", "ALL", "teamIds", List.of(), "selfId", 10L, "offset", 0, "size", 20);
            List<Map<String, Object>> msgAllRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectMessages(ALL)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectMessages",
                    msgAllParams);
            assertThat(rowForTable(msgAllRows, "m").get("key")).isEqualTo("idx_wecom_message_sent_id");

            Map<String, Object> msgConvParams = params(
                    "tenantId", 1L, "customerId", null, "conversationId", "ext-0001", "corpId", null,
                    "scopeType", "ALL", "teamIds", List.of(), "selfId", 10L, "offset", 0, "size", 20);
            List<Map<String, Object>> msgConvRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectMessages(conversationId)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectMessages",
                    msgConvParams);
            assertThat(rowForTable(msgConvRows, "m").get("key")).isEqualTo("idx_wecom_message_conv_id");

            Map<String, Object> msgTeamParams = params(
                    "tenantId", 1L, "customerId", null, "conversationId", null, "corpId", null,
                    "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L, "offset", 0, "size", 20);
            List<Map<String, Object>> msgTeamRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectMessages(TEAM)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectMessages",
                    msgTeamParams);
            assertThat(rowForTable(msgTeamRows, "m").get("key")).isEqualTo("idx_wecom_message_sent_id");

            // 6. ExportDataMapper#page keyset cursor across all 4 ExportTypes (ALL 0/mid/tail + TEAM 0)
            ExportScope exportAll = new ExportScope(ExportScope.Kind.ALL, Set.of(), 10L, Set.of());
            ExportScope exportTeam = new ExportScope(ExportScope.Kind.TEAM, Set.of(10L, 11L), 10L, Set.of());
            record ExportCase(String label, ExportScope scope, long afterId) {}
            for (ExportType exportType : ExportType.values()) {
                long midAfterId = switch (exportType) {
                    case CUSTOMER -> 1500L;
                    case ORDER -> 2500L;
                    case REDEMPTION -> 4500L;
                    case FOLLOWUP -> 500L;
                };
                long tailAfterId = switch (exportType) {
                    case CUSTOMER -> 1950L;
                    case ORDER -> 2950L;
                    case REDEMPTION -> 4950L;
                    case FOLLOWUP -> 950L;
                };
                String driverAlias = switch (exportType) {
                    case CUSTOMER -> "c";
                    case ORDER -> "o";
                    case REDEMPTION -> "r";
                    case FOLLOWUP -> "f";
                };
                List<ExportCase> cases = List.of(
                        new ExportCase("ALL, afterId=0", exportAll, 0L),
                        new ExportCase("ALL, afterId=" + midAfterId, exportAll, midAfterId),
                        new ExportCase("ALL, afterId=" + tailAfterId, exportAll, tailAfterId),
                        new ExportCase("TEAM, afterId=0", exportTeam, 0L));
                for (ExportCase ec : cases) {
                    ExportDataQuery eq = new ExportDataQuery(
                            1L, exportType, ec.scope(), ExportRestriction.none(), ExportFilter.empty(), ec.afterId(), 50);
                    List<Map<String, Object>> expRows = assertNoFullTableScans(
                            conn, "ExportDataMapper.page(" + exportType + ", " + ec.label() + ")",
                            "com.whale.crm.exporting.infrastructure.mapper.ExportDataMapper.page",
                            params("q", eq));
                    Map<String, Object> driverRow = rowForTable(expRows, driverAlias);
                    assertThat(driverRow.get("key")).isEqualTo("PRIMARY");
                    assertThat(String.valueOf(driverRow.get("type"))).isEqualTo("range");
                    assertThat(expRows)
                            .allSatisfy(r -> assertThat(String.valueOf(r.get("Extra"))).doesNotContain("Using filesort"));
                }
            }

            // 7. YouzanMapper#countTodayEvents & countScopedTodayEvents
            Instant todayStart = Instant.parse("2026-04-01T00:00:00Z");
            Instant todayEnd = Instant.parse("2026-04-02T00:00:00Z");
            List<Map<String, Object>> yzTodayRows = assertNoFullTableScans(
                    conn, "YouzanMapper.countTodayEvents",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.countTodayEvents",
                    params("tenantId", 1L, "shopId", "shop-exp",
                            "todayStartUtc", todayStart, "todayEndUtcExclusive", todayEnd));
            assertThat(yzTodayRows.get(0).get("key")).isEqualTo("idx_youzan_event_received");

            CustomerScopeSupport.ScopeQuery teamScope = new CustomerScopeSupport.ScopeQuery("TEAM", List.of(11L), 10L);
            List<Map<String, Object>> yzScopedTodayRows = assertNoFullTableScans(
                    conn, "YouzanMapper.countScopedTodayEvents(TEAM)",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.countScopedTodayEvents",
                    params("tenantId", 1L, "shopId", "shop-exp", "scope", teamScope,
                            "todayStartUtc", todayStart, "todayEndUtcExclusive", todayEnd));
            assertThat(rowForTable(yzScopedTodayRows, "e").get("key")).isEqualTo("idx_youzan_event_received");

            // 8. TaskMapper#search & dutySummary
            List<Map<String, Object>> taskSearchRows = assertNoFullTableScans(
                    conn, "TaskMapper.search",
                    "com.whale.crm.customer.infrastructure.mapper.TaskMapper.search",
                    params("tenantId", 1L, "scopeType", "ALL", "teamIds", List.of(), "selfId", 10L,
                            "assigneeId", 10L, "status", "OPEN", "customerId", null, "offset", 0, "size", 20));
            assertThat(rowForTable(taskSearchRows, "t").get("key")).isEqualTo("idx_task_1");

            List<Map<String, Object>> taskDutyRows = assertNoFullTableScans(
                    conn, "TaskMapper.dutySummary(SELF)",
                    "com.whale.crm.customer.infrastructure.mapper.TaskMapper.dutySummary",
                    params("tenantId", 1L, "scopeType", "SELF", "teamIds", List.of(), "selfId", 10L,
                            "now", Instant.parse("2026-04-10T00:00:00Z"),
                            "dayEndExclusive", Instant.parse("2026-04-11T00:00:00Z")));
            assertThat(String.valueOf(rowForTable(taskDutyRows, "t").get("key")))
                    .isIn("idx_task_1", "idx_task_due_poll");

            // 9. NotificationMapper#searchOwn
            List<Map<String, Object>> notifRows = assertNoFullTableScans(
                    conn, "NotificationMapper.searchOwn",
                    "com.whale.crm.notification.infrastructure.mapper.NotificationMapper.searchOwn",
                    params("tenantId", 1L, "recipientUserId", 10L, "unreadOnly", Boolean.TRUE,
                            "notificationType", null, "offset", 0, "size", 20));
            assertThat(String.valueOf(notifRows.get(0).get("key")))
                    .isIn("idx_notification_unread", "idx_notification_recipient");
            assertThat(String.valueOf(notifRows.get(0).get("Extra"))).doesNotContain("Using filesort");

            // 10. AuditLogMapper#search (with actionCode + without actionCode)
            List<Map<String, Object>> auditActionRows = assertNoFullTableScans(
                    conn, "AuditLogMapper.search(actionCode)",
                    "com.whale.crm.platform.audit.infrastructure.mapper.AuditLogMapper.search",
                    params("tenantId", 1L, "actorUserId", null, "actionCode", "ORDER_PAID",
                            "targetType", null, "offset", 0, "size", 20));
            assertThat(rowForTable(auditActionRows, "a").get("key")).isEqualTo("idx_audit_log_action");
            assertThat(String.valueOf(rowForTable(auditActionRows, "a").get("Extra")))
                    .doesNotContain("Using filesort");

            List<Map<String, Object>> auditTenantRows = assertNoFullTableScans(
                    conn, "AuditLogMapper.search(tenantOnly)",
                    "com.whale.crm.platform.audit.infrastructure.mapper.AuditLogMapper.search",
                    params("tenantId", 1L, "actorUserId", null, "actionCode", null,
                            "targetType", null, "offset", 0, "size", 20));
            assertThat(String.valueOf(rowForTable(auditTenantRows, "a").get("key")))
                    .isIn("idx_audit_log_tenant_id", "PRIMARY");
            assertThat(String.valueOf(rowForTable(auditTenantRows, "a").get("Extra")))
                    .doesNotContain("Using filesort");
        }
    }

    @Test
    void explainVerifiesWave2LiveMapperSqls() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 1. CustomerMapper#search & countSearch with followupDueBefore
            Instant dueCutoff = Instant.parse("2026-04-15T00:00:00Z");
            Map<String, Object> dueParams = customerSearchParams("ALL", List.of(), 10L, dueCutoff);
            List<Map<String, Object>> dueSearchRows = assertNoFullTableScans(
                    conn, "CustomerMapper.search(followupDueBefore)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.search",
                    dueParams);
            Map<String, Object> dueCustomerRow = rowForTable(dueSearchRows, "c");
            assertThat(dueCustomerRow.get("key")).isEqualTo("idx_customer_next_followup");
            assertThat(String.valueOf(dueCustomerRow.get("type"))).isEqualTo("range");

            List<Map<String, Object>> dueCountRows = assertNoFullTableScans(
                    conn, "CustomerMapper.countSearch(followupDueBefore)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.countSearch",
                    dueParams);
            assertThat(rowForTable(dueCountRows, "c").get("key")).isEqualTo("idx_customer_next_followup");
            assertThat(String.valueOf(rowForTable(dueCountRows, "c").get("Extra"))).contains("Using index");

            // 2. YouzanMapper structured columns + crm_youzan_order_latest
            CustomerScopeSupport.ScopeQuery selfScope = new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 10L);
            CustomerScopeSupport.ScopeQuery allScope = new CustomerScopeSupport.ScopeQuery("ALL", List.of(), 10L);

            List<Map<String, Object>> yzScopedEvents = assertNoFullTableScans(
                    conn, "YouzanMapper.countScopedEvents(SELF)",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.countScopedEvents",
                    params("tenantId", 1L, "shopId", "shop-exp", "scope", selfScope));
            assertThat(rowForTable(yzScopedEvents, "customer_link").get("key")).isEqualTo("uk_youzan_customer_link_1");
            assertThat(rowForTable(yzScopedEvents, "om").get("key")).isEqualTo("uk_youzan_order_map_1");
            assertThat(rowForTable(yzScopedEvents, "om2").get("key")).isEqualTo("uk_youzan_order_map_1");

            List<Map<String, Object>> yzCampaignRows = assertNoFullTableScans(
                    conn, "YouzanMapper.countScopedCampaignOrders(SELF)",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.countScopedCampaignOrders",
                    params("tenantId", 1L, "shopId", "shop-exp", "scope", selfScope));
            assertThat(rowForTable(yzCampaignRows, "e").get("key")).isEqualTo("idx_youzan_event_campaign");
            assertThat(rowForTable(yzCampaignRows, "customer_link").get("key")).isEqualTo("uk_youzan_customer_link_1");

            for (CustomerScopeSupport.ScopeQuery scope : List.of(allScope, selfScope)) {
                List<Map<String, Object>> latestPageRows = assertNoFullTableScans(
                        conn, "YouzanMapper.selectLatestOrderEvents(" + scope.scopeType() + ")",
                        "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.selectLatestOrderEvents",
                        params("tenantId", 1L, "shopId", "shop-exp", "scope", scope, "offset", 0, "size", 20));
                Map<String, Object> olRow = rowForTable(latestPageRows, "ol");
                assertThat(olRow.get("key")).isEqualTo("idx_youzan_order_latest_event");
                assertThat(String.valueOf(olRow.get("Extra")))
                        .contains("Using index")
                        .doesNotContain("Using filesort");
                assertThat(rowForTable(latestPageRows, "e").get("key")).isEqualTo("PRIMARY");

                List<Map<String, Object>> latestCountRows = assertNoFullTableScans(
                        conn, "YouzanMapper.countDistinctExternalOrders(" + scope.scopeType() + ")",
                        "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.countDistinctExternalOrders",
                        params("tenantId", 1L, "shopId", "shop-exp", "scope", scope));
                assertThat(String.valueOf(rowForTable(latestCountRows, "ol").get("key")))
                        .isIn("idx_youzan_order_latest_event", "uk_youzan_order_latest_1");
                assertThat(rowForTable(latestCountRows, "e").get("key")).isEqualTo("PRIMARY");
            }

            List<Map<String, Object>> issueNoOrderRows = assertNoFullTableScans(
                    conn, "YouzanMapper.selectIssueIdsWithoutOrderByExternalCustomer",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.selectIssueIdsWithoutOrderByExternalCustomer",
                    params("tenantId", 1L, "shopId", "shop-exp", "externalCustomerId", "yz-ext-0001"));
            assertThat(rowForTable(issueNoOrderRows, "ye").get("key")).isEqualTo("idx_youzan_event_ext_customer");
            assertThat(rowForTable(issueNoOrderRows, "yi").get("key")).isEqualTo("uk_youzan_sync_issue_1");

            // 3. RiskCaseMapper PK fast-path + all 5 delayed fallback queries
            List<Map<String, Object>> resolvedRcRows = assertNoFullTableScans(
                    conn, "RiskCaseMapper.findResolvedCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findResolvedCustomerLinks",
                    params("tenantId", 1L, "riskCaseIds", List.of(1L, 2L, 3L)));
            assertThat(rowForTable(resolvedRcRows, "r").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(resolvedRcRows, "o").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(resolvedRcRows, "c").get("key")).isEqualTo("PRIMARY");

            assertNoFullTableScans(
                    conn, "RiskCaseMapper.findDelayedOrderCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findDelayedOrderCustomerLinks",
                    params("tenantId", 1L, "sourceIds", List.of(2001L, 2002L, 2003L)));

            List<Map<String, Object>> delayedYzRows = assertNoFullTableScans(
                    conn, "RiskCaseMapper.findDelayedYouzanIssueCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findDelayedYouzanIssueCustomerLinks",
                    params("tenantId", 1L, "sourceIds", List.of(901L, 902L, 903L)));
            assertThat(rowForTable(delayedYzRows, "yi").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(delayedYzRows, "ye").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(delayedYzRows, "yom").get("key")).isEqualTo("uk_youzan_order_map_1");
            assertThat(rowForTable(delayedYzRows, "ycl").get("key")).isEqualTo("uk_youzan_customer_link_1");

            assertNoFullTableScans(
                    conn, "RiskCaseMapper.findDelayedRefundCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findDelayedRefundCustomerLinks",
                    params("tenantId", 1L, "sourceIds", List.of(6001L, 6002L, 6003L)));
            assertNoFullTableScans(
                    conn, "RiskCaseMapper.findDelayedEntitlementCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findDelayedEntitlementCustomerLinks",
                    params("tenantId", 1L, "sourceIds", List.of(5001L, 5002L, 5003L)));
            assertNoFullTableScans(
                    conn, "RiskCaseMapper.findDelayedRedemptionCustomerLinks",
                    "com.whale.crm.risk.infrastructure.mapper.RiskCaseMapper.findDelayedRedemptionCustomerLinks",
                    params("tenantId", 1L, "sourceIds", List.of(4001L, 4002L, 4003L)));

            // 4. Customer Timeline 9-way SQL cursor/LIMIT branches
            Instant cursorAt = Instant.parse("2026-08-01T00:00:00Z");

            // TL1: CustomerOwnerHistoryMapper
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl1 = assertNoFullTableScans(
                        conn, "Timeline-1 OwnerHistory(" + mode + ")",
                        "com.whale.crm.customer.infrastructure.mapper.CustomerOwnerHistoryMapper.selectTimelineRowsByCustomerId",
                        params("tenantId", 1L, "customerId", 1001L, "cursorMode", mode,
                                "cursorOccurredAt", cursorAt, "cursorSourceId", 5L, "limit", 21));
                assertThat(rowForTable(tl1, "h").get("key")).isEqualTo("idx_customer_owner_h_1");
            }

            // TL2: FollowupMapper (4-branch UNION ALL, TEAM scope, EQ cursor)
            List<Map<String, Object>> tl2 = assertNoFullTableScans(
                    conn, "Timeline-2 Followup(TEAM, EQ)",
                    "com.whale.crm.customer.infrastructure.mapper.FollowupMapper.selectTimelineEvents",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(11L), "selfId", 10L,
                            "customerId", 1001L,
                            "nextFollowupMode", "EQ", "intentMode", "EQ",
                            "followupMode", "EQ", "blockerMode", "EQ",
                            "cursorOccurredAt", cursorAt, "cursorSourceId", 5L, "limit", 22));
            List<Map<String, Object>> followupBranchRows = tl2.stream()
                    .filter(r -> "f".equals(r.get("table")))
                    .toList();
            assertThat(followupBranchRows).hasSize(4);
            assertThat(followupBranchRows)
                    .allSatisfy(r -> assertThat(r.get("key")).isEqualTo("idx_followup_1"));

            // TL3: TaskMapper
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl3 = assertNoFullTableScans(
                        conn, "Timeline-3 Task(" + mode + ")",
                        "com.whale.crm.customer.infrastructure.mapper.TaskMapper.selectTimeline",
                        params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(11L), "selfId", 10L,
                                "customerId", 1001L, "cursorMode", mode,
                                "cursorOccurredAt", cursorAt, "cursorSourceId", 5L, "limit", 21));
                assertThat(rowForTable(tl3, "t").get("key")).isEqualTo("idx_task_customer");
            }

            // TL4: TradingMapper
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl4 = assertNoFullTableScans(
                        conn, "Timeline-4 Orders(" + mode + ")",
                        "com.whale.crm.trading.infrastructure.mapper.TradingMapper.selectTimelineOrders",
                        params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(11L), "selfId", 10L,
                                "customerId", 1001L, "cursorMode", mode,
                                "cursorOccurredAt", cursorAt, "cursorSourceId", 2005L, "limit", 21));
                assertThat(rowForTable(tl4, "o").get("key")).isEqualTo("idx_order_1");
            }

            // TL5: FulfillmentMapper appointments (ALL + LE)
            FulfillmentScope fulTeam = new FulfillmentScope("TEAM", List.of(11L), 10L, List.of());
            for (String mode : List.of("ALL", "LE")) {
                List<Map<String, Object>> tl5 = assertNoFullTableScans(
                        conn, "Timeline-5 Appointments(" + mode + ")",
                        "com.whale.crm.fulfillment.infrastructure.mapper.FulfillmentMapper.selectTimelineAppointments",
                        params("tenantId", 1L, "scope", fulTeam, "customerId", 1001L,
                                "cursorMode", mode, "cursorOccurredAt", cursorAt,
                                "cursorSourceId", 7005L, "limit", 21));
                assertThat(rowForTable(tl5, "a").get("key")).isEqualTo("idx_appointment_2");
            }

            // TL6: FulfillmentMapper redemptions
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl6 = assertNoFullTableScans(
                        conn, "Timeline-6 Redemptions(" + mode + ")",
                        "com.whale.crm.fulfillment.infrastructure.mapper.FulfillmentMapper.selectTimelineRedemptions",
                        params("tenantId", 1L, "scope", fulTeam, "customerId", 1001L,
                                "cursorMode", mode, "cursorOccurredAt", cursorAt,
                                "cursorSourceId", 4005L, "limit", 21));
                assertThat(rowForTable(tl6, "r").get("key")).isEqualTo("idx_redemption_2");
            }

            // TL7: RefundMapper
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl7 = assertNoFullTableScans(
                        conn, "Timeline-7 Refunds(" + mode + ")",
                        "com.whale.crm.refund.infrastructure.mapper.RefundMapper.findTimelineRefunds",
                        params("tenantId", 1L, "customerId", 1001L,
                                "scopeType", "TEAM", "teamIds", List.of(11L), "selfId", 10L,
                                "cursorMode", mode, "cursorOccurredAt", cursorAt,
                                "cursorSourceId", 6005L, "limit", 21));
                assertThat(rowForTable(tl7, "r").get("key")).isEqualTo("idx_refund_customer");
            }

            // TL8: CustomerTierMapper
            for (String mode : List.of("ALL", "EQ")) {
                List<Map<String, Object>> tl8 = assertNoFullTableScans(
                        conn, "Timeline-8 TierHistory(" + mode + ")",
                        "com.whale.crm.customer.tier.infrastructure.mapper.CustomerTierMapper.selectTimelineHistory",
                        params("tenantId", 1L, "customerId", 1001L, "cursorMode", mode,
                                "cursorOccurredAt", cursorAt, "cursorSourceId", 5L, "limit", 21));
                assertThat(tl8.get(0).get("key")).isEqualTo("idx_customer_tier_history_customer");
            }

            // TL9: ComplaintMapper
            for (String mode : List.of("ALL", "EQ")) {
                ComplaintTimelineQuery cq = new ComplaintTimelineQuery(
                        1L, "TEAM", List.of(11L), 10L, 1001L, mode, cursorAt, 5L, 21);
                List<Map<String, Object>> tl9 = assertNoFullTableScans(
                        conn, "Timeline-9 Complaints(" + mode + ")",
                        "com.whale.crm.complaint.infrastructure.mapper.ComplaintMapper.findTimelineCases",
                        params("q", cq));
                assertThat(rowForTable(tl9, "cc").get("key")).isEqualTo("idx_complaint_case_customer");
            }

            // 5. CustomerSegmentMapper#searchMembers & countMembers
            CustomerSegmentCriteria purchasedCriteria = new CustomerSegmentCriteria(
                    null, null, null, List.of(701L), null, null);
            List<Map<String, Object>> segPurchasedRows = assertNoFullTableScans(
                    conn, "CustomerSegmentMapper.searchMembers(purchasedProjectIds)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper.searchMembers",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L,
                            "filters", purchasedCriteria, "offset", 0, "size", 20));
            assertThat(rowForTable(segPurchasedRows, "pe").get("key")).isNotNull();
            assertThat(rowForTable(segPurchasedRows, "pl").get("key")).isEqualTo("idx_entitlement_ledg_1");

            CustomerSegmentCriteria attentionCriteria = new CustomerSegmentCriteria(
                    null, null, List.of(701L), null, null, null);
            List<Map<String, Object>> segAttentionRows = assertNoFullTableScans(
                    conn, "CustomerSegmentMapper.searchMembers(attentionProjectIds)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper.searchMembers",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L,
                            "filters", attentionCriteria, "offset", 0, "size", 20));
            assertThat(rowForTable(segAttentionRows, "ap").get("key")).isEqualTo("idx_customer_attention_project_filter");

            CustomerSegmentCriteria redeemedCriteria = new CustomerSegmentCriteria(
                    null, null, null, null, List.of(701L), 1);
            List<Map<String, Object>> segRedeemedRows = assertNoFullTableScans(
                    conn, "CustomerSegmentMapper.searchMembers(redeemedProjectIds)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper.searchMembers",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L,
                            "filters", redeemedCriteria, "offset", 0, "size", 20));
            assertThat(String.valueOf(rowForTable(segRedeemedRows, "rl").get("key")))
                    .isIn("idx_entitlement_ledg_1", "fk_entitlement_ledg_entitlement_id");

            assertNoFullTableScans(
                    conn, "CustomerSegmentMapper.countMembers(purchasedProjectIds)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper.countMembers",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L,
                            "filters", purchasedCriteria));
        }
    }

    @Test
    void explainVerifiesPhase2LiveMapperSqls() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            // 1. WecomAnalyticsMapper#appointmentFacts
            List<Map<String, Object>> wecomAptRows = assertNoFullTableScans(
                    conn, "WecomAnalyticsMapper.appointmentFacts(TEAM)",
                    "com.whale.crm.reporting.infrastructure.mapper.WecomAnalyticsMapper.appointmentFacts",
                    params("tenantId", 1L,
                            "fromUtc", Instant.parse("2026-04-01T00:00:00Z"),
                            "toUtcExclusive", Instant.parse("2026-04-02T00:00:00Z"),
                            "scopeType", "TEAM", "teamIds", Set.of(11L),
                            "selfId", 10L, "employeeId", null));
            assertThat(rowForTable(wecomAptRows, "a").get("key")).isEqualTo("idx_appointment_confirmed_at");
            assertThat(rowForTable(wecomAptRows, "c").get("key")).isEqualTo("PRIMARY");
            assertThat(wecomAptRows.stream().map(r -> String.valueOf(r.get("table"))))
                    .noneMatch(t -> t.contains("appointment_history"));

            // 2. WecomMapper#selectContacts (ALL deferred-join + TEAM non-empty + TEAM empty)
            List<Map<String, Object>> contactAllRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectContacts(ALL)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                    params("tenantId", 1L, "scopeType", "ALL", "teamIds", List.of(), "selfId", 10L));
            Map<String, Object> derivedInner = rowForTable(contactAllRows, "c", "DERIVED");
            assertThat(derivedInner.get("key")).isEqualTo("idx_wecom_contact_unlinked");
            assertThat(String.valueOf(derivedInner.get("Extra")))
                    .contains("Using index")
                    .doesNotContain("Using filesort");
            assertThat(rowForTable(contactAllRows, "c", "PRIMARY").get("key")).isEqualTo("PRIMARY");

            List<Map<String, Object>> contactSelfRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectContacts(SELF)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                    params("tenantId", 1L, "scopeType", "SELF", "teamIds", List.of(), "selfId", 10L));
            assertThat(rowForTable(contactSelfRows, "c", "PRIMARY").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(contactSelfRows, "c", "DERIVED").get("key")).isEqualTo("idx_wecom_contact_1");
            assertThat(rowForTable(contactSelfRows, "cu").get("key")).isEqualTo("idx_customer_1");

            List<Map<String, Object>> contactTeamEmptyRows = assertNoFullTableScans(
                    conn, "WecomMapper.selectContacts(TEAM, teamIds=[])",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(), "selfId", 10L));
            assertThat(rowForTable(contactTeamEmptyRows, "c", "PRIMARY").get("key")).isEqualTo("PRIMARY");
            assertThat(rowForTable(contactTeamEmptyRows, "c", "DERIVED").get("key")).isEqualTo("idx_wecom_contact_1");
            assertThat(rowForTable(contactTeamEmptyRows, "cu").get("key")).isEqualTo("idx_customer_1");

            for (List<Long> teamIds : List.of(List.of(10L, 11L), List.of(11L))) {
                List<Map<String, Object>> contactTeamRows = assertNoFullTableScans(
                        conn, "WecomMapper.selectContacts(TEAM, teamIds=" + teamIds + ")",
                        "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                        params("tenantId", 1L, "scopeType", "TEAM", "teamIds", teamIds, "selfId", 10L));
                assertThat(rowForTable(contactTeamRows, "c", "PRIMARY").get("key")).isEqualTo("PRIMARY");
                List<Map<String, Object>> cuRows = contactTeamRows.stream()
                        .filter(r -> "cu".equals(String.valueOf(r.get("table"))))
                        .toList();
                assertThat(cuRows).hasSize(2);
                assertThat(cuRows).allSatisfy(r -> {
                    assertThat(String.valueOf(r.get("type"))).isNotEqualToIgnoringCase("ALL");
                    assertThat(String.valueOf(r.get("key"))).isIn("idx_customer_1", "idx_customer_team");
                });
                List<Map<String, Object>> innerCRows = contactTeamRows.stream()
                        .filter(r -> "c".equals(String.valueOf(r.get("table")))
                                && Set.of("DERIVED", "UNION")
                                        .contains(String.valueOf(r.get("select_type")).toUpperCase()))
                        .toList();
                assertThat(innerCRows).hasSize(2);
                assertThat(innerCRows).allSatisfy(r ->
                        assertThat(r.get("key")).isEqualTo("idx_wecom_contact_1"));
            }

            // 3. MaterialMapper#search (no keyword + keyword="synthetic") & count (institution scoped)
            MaterialScopeQuery matScope = new MaterialScopeQuery("ALL", List.of(), 10L, true, true, true);
            for (String keyword : new String[]{null, "synthetic"}) {
                MaterialSearchCriteria criteria = new MaterialSearchCriteria(
                        keyword, null, null, null, null, null, null, 0, 20);
                List<Map<String, Object>> matSearchRows = assertNoFullTableScans(
                        conn, "MaterialMapper.search(keyword=" + keyword + ")",
                        "com.whale.crm.material.infrastructure.mapper.MaterialMapper.search",
                        params("tenantId", 1L, "scope", matScope, "criteria", criteria, "offset", 0, "size", 20));
                assertThat(rowForTable(matSearchRows, "r_lat").get("key")).isEqualTo("PRIMARY");
                assertThat(rowForTable(matSearchRows, "r_cur").get("key")).isEqualTo("PRIMARY");
                assertThat(matSearchRows)
                        .noneSatisfy(r -> assertThat(String.valueOf(r.get("Extra")))
                                .contains("Windowing"));
                if ("synthetic".equals(keyword)) {
                    Map<String, Object> mtRow = rowForTable(matSearchRows, "mt");
                    assertThat(mtRow.get("key")).isEqualTo("uk_material_tag_1");
                    assertThat(String.valueOf(mtRow.get("Extra"))).contains("Using index");
                }
            }
            List<Map<String, Object>> matCountRows = assertNoFullTableScans(
                    conn, "MaterialMapper.count(institutionId=2001)",
                    "com.whale.crm.material.infrastructure.mapper.MaterialMapper.count",
                    params("tenantId", 1L, "scope", matScope,
                            "criteria", new MaterialSearchCriteria(
                                    "synthetic", null, 2001L, null, null, null, null, 0, 20)));
            assertThat(rowForTable(matCountRows, "m").get("key")).isEqualTo("idx_material_institution");
            assertThat(rowForTable(matCountRows, "mt").get("key")).isEqualTo("uk_material_tag_1");

            // 4. InstitutionMapper#searchProfiles & searchChanges
            List<Map<String, Object>> instProfileRows = assertNoFullTableScans(
                    conn, "InstitutionMapper.searchProfiles",
                    "com.whale.crm.institution.infrastructure.mapper.InstitutionMapper.searchProfiles",
                    params("tenantId", 1L, "scope", InstitutionScope.all(),
                            "keyword", null, "merchantKeyword", null,
                            "status", "ACTIVE", "cityCode", null, "offset", 0, "size", 20));
            assertThat(instProfileRows.get(0).get("key")).isEqualTo("idx_institution_archived_status_id");
            assertThat(String.valueOf(instProfileRows.get(0).get("Extra"))).doesNotContain("Using filesort");

            List<Map<String, Object>> instChangeRows = assertNoFullTableScans(
                    conn, "InstitutionMapper.searchChanges",
                    "com.whale.crm.institution.infrastructure.mapper.InstitutionMapper.searchChanges",
                    params("tenantId", 1L, "scope", InstitutionScope.all(),
                            "institutionId", null, "status", "PENDING_REVIEW", "offset", 0, "size", 20));
            assertThat(instChangeRows.get(0).get("key")).isEqualTo("idx_profile_change_status_id");
            assertThat(String.valueOf(instChangeRows.get(0).get("Extra"))).doesNotContain("Using filesort");

            // Representative EXPLAIN ANALYZE timings on the 1,000-row seeded dataset
            logExplainAnalyze(conn, "CustomerMapper.search(followupDueBefore)",
                    "com.whale.crm.customer.infrastructure.mapper.CustomerMapper.search",
                    customerSearchParams("ALL", List.of(), 10L, Instant.parse("2026-04-15T00:00:00Z")));
            logExplainAnalyze(conn, "YouzanMapper.selectLatestOrderEvents(SELF)",
                    "com.whale.crm.integration.youzan.infrastructure.mapper.YouzanMapper.selectLatestOrderEvents",
                    params("tenantId", 1L, "shopId", "shop-exp",
                            "scope", new CustomerScopeSupport.ScopeQuery("SELF", List.of(), 10L),
                            "offset", 0, "size", 20));
            logExplainAnalyze(conn, "FollowupMapper.selectTimelineEvents(TEAM, EQ)",
                    "com.whale.crm.customer.infrastructure.mapper.FollowupMapper.selectTimelineEvents",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(11L), "selfId", 10L,
                            "customerId", 1001L,
                            "nextFollowupMode", "EQ", "intentMode", "EQ",
                            "followupMode", "EQ", "blockerMode", "EQ",
                            "cursorOccurredAt", Instant.parse("2026-08-01T00:00:00Z"),
                            "cursorSourceId", 5L, "limit", 22));
            logExplainAnalyze(conn, "WecomMapper.selectContacts(ALL)",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                    params("tenantId", 1L, "scopeType", "ALL", "teamIds", List.of(), "selfId", 10L));
            logExplainAnalyze(conn, "WecomMapper.selectContacts(TEAM, teamIds=[10, 11])",
                    "com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper.selectContacts",
                    params("tenantId", 1L, "scopeType", "TEAM", "teamIds", List.of(10L, 11L), "selfId", 10L));
            ExportScope exportAllScope = new ExportScope(ExportScope.Kind.ALL, Set.of(), 10L, Set.of());
            logExplainAnalyze(conn, "ExportDataMapper.page(ORDER, afterId=0)",
                    "com.whale.crm.exporting.infrastructure.mapper.ExportDataMapper.page",
                    params("q", new ExportDataQuery(
                            1L, ExportType.ORDER, exportAllScope, ExportRestriction.none(),
                            ExportFilter.empty(), 0L, 50)));
            logExplainAnalyze(conn, "ExportDataMapper.page(REDEMPTION, afterId=0)",
                    "com.whale.crm.exporting.infrastructure.mapper.ExportDataMapper.page",
                    params("q", new ExportDataQuery(
                            1L, ExportType.REDEMPTION, exportAllScope, ExportRestriction.none(),
                            ExportFilter.empty(), 0L, 50)));
            logExplainAnalyze(conn, "MaterialMapper.search(keyword=synthetic)",
                    "com.whale.crm.material.infrastructure.mapper.MaterialMapper.search",
                    params("tenantId", 1L, "scope", matScope,
                            "criteria", new MaterialSearchCriteria(
                                    "synthetic", null, null, null, null, null, null, 0, 20),
                            "offset", 0, "size", 20));
        }
    }

    private static Configuration loadMappers() throws Exception {
        Configuration cfg = new Configuration();
        cfg.addMapper(WecomMapper.class);
        List<String> xmls = List.of(
                "mapper/customer/CustomerMapper.xml",
                "mapper/customer/CustomerSegmentMapper.xml",
                "mapper/customer/CustomerTagMapper.xml",
                "mapper/customer/CustomerOwnerHistoryMapper.xml",
                "mapper/customer/FollowupMapper.xml",
                "mapper/customer/TaskMapper.xml",
                "mapper/customer/CustomerTierMapper.xml",
                "mapper/reporting/ReportingMapper.xml",
                "mapper/reporting/WecomAnalyticsMapper.xml",
                "mapper/exporting/ExportDataMapper.xml",
                "mapper/integration/youzan/YouzanMapper.xml",
                "mapper/risk/RiskCaseMapper.xml",
                "mapper/trading/TradingMapper.xml",
                "mapper/fulfillment/FulfillmentMapper.xml",
                "mapper/refund/RefundMapper.xml",
                "mapper/complaint/ComplaintMapper.xml",
                "mapper/material/MaterialMapper.xml",
                "mapper/institution/InstitutionMapper.xml",
                "mapper/notification/NotificationMapper.xml",
                "mapper/audit/AuditLogMapper.xml");
        for (String resource : xmls) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        return cfg;
    }

    private static List<Map<String, Object>> explainBoundSql(
            Connection conn, String statementId, Map<String, Object> params) throws Exception {
        MappedStatement ms = mybatisConfig.getMappedStatement(statementId);
        BoundSql boundSql = ms.getBoundSql(params);
        ParameterHandler ph = new DefaultParameterHandler(ms, params, boundSql);
        try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + boundSql.getSql())) {
            ph.setParameters(ps);
            List<Map<String, Object>> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static void logExplainAnalyze(
            Connection conn, String label, String statementId, Map<String, Object> params) throws Exception {
        MappedStatement ms = mybatisConfig.getMappedStatement(statementId);
        BoundSql boundSql = ms.getBoundSql(params);
        ParameterHandler ph = new DefaultParameterHandler(ms, params, boundSql);
        try (PreparedStatement ps = conn.prepareStatement("EXPLAIN ANALYZE " + boundSql.getSql())) {
            ph.setParameters(ps);
            StringBuilder tree = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (tree.length() > 0) {
                        tree.append('\n');
                    }
                    tree.append(rs.getString(1));
                }
            }
            assertThat(tree.toString()).isNotBlank();
            LOG.info("EXPLAIN ANALYZE [{}]:\n{}", label, tree);
        }
    }

    private static List<Map<String, Object>> assertNoFullTableScans(
            Connection conn, String label, String statementId, Map<String, Object> params) throws Exception {
        List<Map<String, Object>> rows = explainBoundSql(conn, statementId, params);
        assertThat(rows).as("EXPLAIN rows for %s (%s)", label, statementId).isNotEmpty();
        LOG.info("EXPLAIN [{}] -> {}", label, rows);
        for (Map<String, Object> row : rows) {
            String table = row.get("table") == null ? "" : String.valueOf(row.get("table"));
            String extra = row.get("Extra") == null ? "" : String.valueOf(row.get("Extra"));
            if (table.startsWith("<") || extra.contains("Table function:")) {
                continue;
            }
            String type = String.valueOf(row.get("type"));
            String key = row.get("key") == null ? null : String.valueOf(row.get("key"));
            assertThat(type)
                    .as("%s table=%s type!=ALL -> %s", label, table, row)
                    .isNotEqualToIgnoringCase("ALL");
            assertThat(key)
                    .as("%s table=%s key!=null -> %s", label, table, row)
                    .isNotNull();
        }
        return rows;
    }

    private static Map<String, Object> rowForTable(List<Map<String, Object>> rows, String tableAlias) {
        return rows.stream()
                .filter(r -> tableAlias.equals(String.valueOf(r.get("table"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No EXPLAIN row for table=" + tableAlias + " in " + rows));
    }

    private static Map<String, Object> rowForTable(
            List<Map<String, Object>> rows, String tableAlias, String selectType) {
        return rows.stream()
                .filter(r -> tableAlias.equals(String.valueOf(r.get("table")))
                        && selectType.equalsIgnoreCase(String.valueOf(r.get("select_type"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No EXPLAIN row for table=" + tableAlias + ", select_type=" + selectType + " in " + rows));
    }

    private static Map<String, Object> customerSearchParams(
            String scopeType, List<Long> teamIds, long selfId, Instant followupDueBefore) {
        return params(
                "tenantId", 1L,
                "scopeType", scopeType,
                "teamIds", teamIds,
                "selfId", selfId,
                "keyword", null,
                "mobileHashKeyword", null,
                "status", null,
                "cityCode", null,
                "sourceCode", null,
                "channelCode", null,
                "tierCode", null,
                "ownerId", null,
                "followupDueBefore", followupDueBefore,
                "offset", 0,
                "size", 20);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static void seedDataset(MysqlDataSource ds, JdbcTemplate jdbc) throws Exception {
        SingleConnectionDataSource singleConnDs = new SingleConnectionDataSource(ds.getConnection(), true);
        try {
            singleConnDs.getConnection().setAutoCommit(false);
            JdbcTemplate txJdbc = new JdbcTemplate(singleConnDs);
            seedRows(txJdbc);
            singleConnDs.getConnection().commit();
        } finally {
            singleConnDs.destroy();
        }

        List<String> tables = List.of(
                "sys_org", "sys_team", "sys_user", "crm_file_object", "crm_project", "crm_package",
                "crm_package_version", "crm_sku", "crm_institution",
                "crm_customer", "crm_customer_attention_project", "crm_customer_tag", "crm_customer_identity",
                "crm_customer_owner_history",
                "crm_followup", "crm_task",
                "crm_order", "crm_order_item", "crm_payment_txn",
                "crm_entitlement", "crm_entitlement_ledger", "crm_appointment", "crm_appointment_item",
                "crm_redemption", "crm_redemption_line", "crm_refund", "crm_refund_payment",
                "crm_customer_tier_history", "crm_complaint_keyword", "crm_complaint_hit", "crm_complaint_case",
                "crm_youzan_event", "crm_youzan_order_map", "crm_youzan_customer_link",
                "crm_youzan_order_latest", "crm_youzan_sync_issue", "crm_risk_case",
                "crm_wecom_archive_member", "crm_wecom_staff_account", "crm_wecom_room",
                "crm_wecom_room_participant", "crm_wecom_contact", "crm_wecom_message",
                "crm_material", "crm_material_revision", "crm_material_tag",
                "crm_profile_change_request", "crm_notification", "crm_audit_log");
        for (String table : tables) {
            jdbc.execute("ANALYZE TABLE `" + table + "`");
        }
    }

    private static void seedRows(JdbcTemplate jdbc) {
        for (int i = 1; i <= 50; i++) {
            String code = String.format("%02d", i);
            long tenant = i <= 45 ? 1L : 2L;
            jdbc.update("INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (?,?,?,?,'ACTIVE')",
                    i, tenant, "ORG_" + code, "组织" + code);
            long teamId = 9L + i;
            jdbc.update("INSERT INTO sys_team (id,tenant_id,org_id,team_name,status) VALUES (?,1,1,?,'ACTIVE')",
                    teamId, "团队" + code);
            jdbc.update("INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,"
                            + "user_type,status) VALUES (?,1,1,?,?,?,'hash','EMPLOYEE','ACTIVE')",
                    teamId, teamId, "staff_" + code, "管家" + code);
        }

        jdbc.update("INSERT INTO crm_file_object (id,tenant_id,object_key,mime_type,size_bytes,sha256,classification,"
                + "scan_status,uploaded_by) VALUES (901,1,'mat/exp/901.pdf','application/pdf',1024,'"
                + "c".repeat(64) + "','MATERIAL','CLEAN',10)");
        jdbc.update("INSERT INTO crm_package (id,tenant_id,package_code,package_name,status) "
                + "VALUES (1,1,'PKG_01','套餐1','ACTIVE')");
        jdbc.update("INSERT INTO crm_package_version (id,tenant_id,package_id,version_no,selection_mode,choice_count,"
                + "status) VALUES (1,1,1,1,'FIXED',1,'ACTIVE')");
        for (int p = 1; p <= 5; p++) {
            jdbc.update("INSERT INTO crm_project (id,tenant_id,project_code,project_name,list_price_fen,status) "
                    + "VALUES (?,1,?,?,10000,'ACTIVE')", 700L + p, "PRJ_" + p, "项目" + p);
            jdbc.update("INSERT INTO crm_sku (id,tenant_id,sku_code,package_version_id,spec_name,sale_price_fen,status) "
                    + "VALUES (?,1,?,1,'1次',10000,'ACTIVE')", 800L + p, "SKU_" + p);
        }

        jdbc.update("INSERT INTO crm_complaint_keyword (id,tenant_id,keyword,status,created_by) "
                + "VALUES (1,1,'投诉关键词','ACTIVE',10)");

        for (int i = 1; i <= 10; i++) {
            long staffId = 9L + i;
            jdbc.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code) "
                    + "VALUES (1,'ww-exp',?,1,'MOCK')", staffId);
            jdbc.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                    + "VALUES (1,'ww-exp',?,?,1)", "wecom_u_" + staffId, staffId);
        }
        List<Object[]> wecomRooms = new ArrayList<>(100);
        List<Object[]> wecomRoomParticipants = new ArrayList<>(200);
        for (int r = 1; r <= 100; r++) {
            String roomId = "room-" + String.format("%03d", r);
            wecomRooms.add(new Object[]{roomId, "群" + r});
            wecomRoomParticipants.add(new Object[]{roomId, "wecom_u_10", "STAFF", 10L, null});
            String extUser = "ext-" + String.format("%04d", r);
            wecomRoomParticipants.add(new Object[]{roomId, extUser, "EXTERNAL", null, extUser});
        }
        jdbc.batchUpdate("INSERT INTO crm_wecom_room (tenant_id,corp_id,room_id,display_name,source_evidence_ref,"
                + "membership_confirmed_at,created_by) VALUES (1,'ww-exp',?,?,'ev','2026-04-01 00:00:00.000',10)",
                wecomRooms);
        jdbc.batchUpdate("INSERT INTO crm_wecom_room_participant (tenant_id,corp_id,room_id,wecom_userid,"
                + "participant_type,staff_user_id,external_userid,status,confirmed_at) "
                + "VALUES (1,'ww-exp',?,?,?,?,?,'ACTIVE','2026-04-01 00:00:00.000')", wecomRoomParticipants);

        List<Object[]> institutions = new ArrayList<>(SEED_ROWS);
        List<Object[]> customers = new ArrayList<>(SEED_ROWS);
        List<Object[]> attentionProjects = new ArrayList<>(SEED_ROWS);
        List<Object[]> customerTags = new ArrayList<>(SEED_ROWS + 3);
        List<Object[]> identities = new ArrayList<>(SEED_ROWS);
        List<Object[]> ownerHistories = new ArrayList<>(SEED_ROWS);
        List<Object[]> followups = new ArrayList<>(SEED_ROWS);
        List<Object[]> tasks = new ArrayList<>(SEED_ROWS);
        List<Object[]> orders = new ArrayList<>(SEED_ROWS);
        List<Object[]> orderItems = new ArrayList<>(SEED_ROWS);
        List<Object[]> payments = new ArrayList<>(SEED_ROWS);
        List<Object[]> entitlements = new ArrayList<>(SEED_ROWS);
        List<Object[]> entitlementLedgers = new ArrayList<>(SEED_ROWS + 500);
        List<Object[]> appointments = new ArrayList<>(SEED_ROWS);
        List<Object[]> appointmentItems = new ArrayList<>(SEED_ROWS);
        List<Object[]> redemptions = new ArrayList<>(SEED_ROWS);
        List<Object[]> redemptionLines = new ArrayList<>(SEED_ROWS);
        List<Object[]> refunds = new ArrayList<>(SEED_ROWS);
        List<Object[]> refundPayments = new ArrayList<>(SEED_ROWS);
        List<Object[]> tierHistories = new ArrayList<>(SEED_ROWS);
        List<Object[]> complaintHits = new ArrayList<>(SEED_ROWS);
        List<Object[]> complaintCases = new ArrayList<>(SEED_ROWS);
        List<Object[]> youzanEvents = new ArrayList<>(SEED_ROWS);
        List<Object[]> youzanOrderMaps = new ArrayList<>();
        List<Object[]> youzanCustomerLinks = new ArrayList<>(SEED_ROWS);
        List<Object[]> youzanOrderLatests = new ArrayList<>(SEED_ROWS);
        List<Object[]> youzanSyncIssues = new ArrayList<>(SEED_ROWS);
        List<Object[]> riskCases = new ArrayList<>(SEED_ROWS);
        List<Object[]> wecomContacts = new ArrayList<>(SEED_ROWS);
        List<Object[]> wecomMessages = new ArrayList<>(SEED_ROWS);
        List<Object[]> materials = new ArrayList<>(SEED_ROWS);
        List<Object[]> materialRevisions = new ArrayList<>(SEED_ROWS + 250);
        List<Object[]> materialUpdates = new ArrayList<>(SEED_ROWS);
        List<Object[]> materialTags = new ArrayList<>(SEED_ROWS);
        List<Object[]> profileChanges = new ArrayList<>(SEED_ROWS);
        List<Object[]> notifications = new ArrayList<>(SEED_ROWS);
        List<Object[]> auditLogs = new ArrayList<>(SEED_ROWS);

        for (int i = 1; i <= SEED_ROWS; i++) {
            String code = String.format("%04d", i);
            long custId = 1000L + i;
            long tlCustId = i <= 25 ? 1001L : custId;
            long instId = 2000L + i;
            long ownerStaffId = 10L + (i % 50);
            long teamId = 10L + (i % 50);
            int day = (i % 28) + 1;
            int hour = (i % 12) + 8;
            String reportWindowDt = String.format("2026-04-01 %02d:%02d:00.000", hour, i % 60);
            String spreadDt = i <= 50
                    ? reportWindowDt
                    : String.format("2026-%02d-%02d %02d:%02d:00.000", 5 + (i % 7), day, hour, i % 60);
            String followDueDt = i <= 50
                    ? "2026-04-01 10:00:00.000"
                    : (i <= 700 ? String.format("2026-%02d-%02d 10:00:00.000", 8 + (i % 4), day) : null);

            institutions.add(new Object[]{
                    instId, "EXP-INST-" + code, "机构" + code,
                    (i <= 600 ? "ACTIVE" : "SUSPENDED"), (i <= 800 ? 0 : 1)
            });
            customers.add(new Object[]{
                    custId, ownerStaffId, teamId, "EXP-C-" + code, "客户" + code, followDueDt, spreadDt
            });
            attentionProjects.add(new Object[]{custId, 701L + (i % 5)});
            customerTags.add(new Object[]{custId, String.format("TAG_%02d", (i % 20) + 1)});
            if (i <= 3) {
                customerTags.add(new Object[]{1001L, "EXTRA_TAG_" + i});
            }
            identities.add(new Object[]{custId, "EXT_ID_" + code});
            ownerHistories.add(new Object[]{tlCustId, spreadDt});

            followups.add(new Object[]{tlCustId, spreadDt, (i <= 30 ? spreadDt : null), spreadDt});
            tasks.add(new Object[]{
                    tlCustId, 10L + (i % 50), (i % 4 == 0 ? "OPEN" : "DONE"), spreadDt, "TSK-" + code, spreadDt
            });

            long orderId = 2000L + i;
            String orderStatus = i <= 750 ? "PAID" : "CANCELED";
            orders.add(new Object[]{
                    orderId, "EXP-O-" + code, tlCustId, (i <= 50 ? 9001L : null), orderStatus,
                    spreadDt, spreadDt, spreadDt
            });
            long orderItemId = 3000L + i;
            orderItems.add(new Object[]{orderItemId, orderId, 801L + (i % 5)});
            payments.add(new Object[]{orderId, "PAY-" + code, (i <= 750 ? "CONFIRMED" : "PENDING"), spreadDt});

            long entId = 5000L + i;
            entitlements.add(new Object[]{entId, tlCustId, orderItemId, 701L + (i % 5)});
            entitlementLedgers.add(new Object[]{entId, "GRANT", 2, "ORDER", "ORD-" + code, "ENT-GRANT-" + code});
            if (i <= 500) {
                entitlementLedgers.add(new Object[]{
                        entId, "REDEEM", -1, "REDEMPTION", "RED-" + code, "ENT-REDEEM-" + code
                });
            }

            long aptId = 7000L + i;
            appointments.add(new Object[]{
                    aptId, "EXP-APT-" + code, tlCustId, instId, spreadDt, spreadDt, spreadDt, spreadDt, spreadDt
            });
            long aptItemId = 7500L + i;
            appointmentItems.add(new Object[]{aptItemId, aptId, entId, 701L + (i % 5)});

            long redId = 4000L + i;
            redemptions.add(new Object[]{
                    redId, aptId, tlCustId, instId, spreadDt, "EV-" + code, "RED-IDEM-" + code, spreadDt, spreadDt
            });
            redemptionLines.add(new Object[]{redId, aptItemId, entId, 701L + (i % 5)});

            long refundId = 6000L + i;
            refunds.add(new Object[]{
                    refundId, "EXP-RF-" + code, orderId, tlCustId, (i <= 50 ? "SUCCEEDED" : "REQUESTED"), spreadDt
            });
            refundPayments.add(new Object[]{
                    refundId, "EXT-RF-" + code, (i <= 50 ? "SUCCEEDED" : "PENDING"), spreadDt
            });

            tierHistories.add(new Object[]{tlCustId, spreadDt, spreadDt});

            long hitId = 8000L + i;
            complaintHits.add(new Object[]{hitId, tlCustId, "F-" + code});
            complaintCases.add(new Object[]{9000L + i, "CC-" + code, hitId, tlCustId, spreadDt});

            long yzEvId = 10000L + i;
            String shopId = i <= 100 ? "shop-exp" : "shop-other-" + (i % 10);
            String extOrderNo = "YZ-EXP-" + code;
            String extCustNo = "yz-ext-" + code;
            Long campaignId = (i <= 10) ? 9001L : null;
            Long yzOrderId = (i <= 800 && i != 1) ? orderId : null;
            String yzReceivedDt = i <= 5
                    ? reportWindowDt
                    : String.format("2026-%02d-%02d %02d:%02d:00.000", 5 + (i % 7), day, hour, i % 60);
            youzanEvents.add(new Object[]{
                    yzEvId, shopId, "yz-ev-" + code, extOrderNo, extCustNo, campaignId, yzReceivedDt, yzOrderId
            });
            if (yzOrderId != null) {
                youzanOrderMaps.add(new Object[]{shopId, extOrderNo, orderId, spreadDt});
            }
            youzanCustomerLinks.add(new Object[]{shopId, extCustNo, custId});
            youzanOrderLatests.add(new Object[]{shopId, extOrderNo, yzEvId});
            youzanSyncIssues.add(new Object[]{(long) i, yzEvId});

            if (i <= 800) {
                riskCases.add(new Object[]{
                        (long) i, "EXP-RC-" + code, "ORDER_PAYMENT_CASH", "ORDER",
                        String.valueOf(orderId), custId, orderId
                });
            } else {
                riskCases.add(new Object[]{
                        (long) i, "EXP-RC-" + code, "YOUZAN_SYNC_FAILED", "YOUZAN_SYNC_ISSUE",
                        String.valueOf(i), null, null
                });
            }

            wecomContacts.add(new Object[]{"ext-" + code, (i <= 150 ? null : custId)});
            wecomMessages.add(new Object[]{
                    "msg-" + code, (long) i, (i <= 150 ? null : custId), "ext-" + code, "ext-" + code,
                    spreadDt, "obj-" + code, "c".repeat(64)
            });

            long matId = 11000L + i;
            long rev1Id = 12000L + (i * 2L) - 1;
            long rev2Id = 12000L + (i * 2L);
            String tagCode = i <= 50 ? "synthetic" : String.format("tag_%02d", (i % 20) + 1);
            materials.add(new Object[]{matId, "EXP-MAT-" + code, "素材" + code});
            materialRevisions.add(new Object[]{
                    rev1Id, matId, 1, "素材" + code, "[\"" + tagCode + "\"]", "APPROVED"
            });
            long latestRevId = rev1Id;
            if (i <= 250) {
                materialRevisions.add(new Object[]{
                        rev2Id, matId, 2, "素材" + code + "-v2", "[\"" + tagCode + "\"]", "PENDING"
                });
                latestRevId = rev2Id;
            }
            materialUpdates.add(new Object[]{latestRevId, rev1Id, matId});
            materialTags.add(new Object[]{matId, tagCode});

            String pcrStatus = i <= 150 ? "PENDING_REVIEW" : "APPROVED";
            profileChanges.add(new Object[]{
                    instId, instId, pcrStatus, spreadDt, (i <= 150 ? "INST:" + instId : null)
            });
            notifications.add(new Object[]{
                    10L + (i % 10), code, "DEDUPE-" + code, spreadDt, (i % 5 == 0 ? null : spreadDt)
            });
            auditLogs.add(new Object[]{(i <= 50 ? "ORDER_PAID" : "ACT_" + (i % 20)), String.valueOf(orderId)});
        }

        jdbc.batchUpdate("INSERT INTO crm_institution (id,tenant_id,institution_no,full_name,status,settlement_type,"
                + "archived) VALUES (?,1,?,?,?,'MONTHLY',?)", institutions);
        jdbc.batchUpdate("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,team_id,customer_no,display_name,"
                + "next_followup_at,deleted,created_at) VALUES (?,1,?,?,?,?,?,0,?)", customers);
        jdbc.batchUpdate("INSERT INTO crm_customer_attention_project (tenant_id,customer_id,project_id,created_by) "
                + "VALUES (1,?,?,10)", attentionProjects);
        jdbc.batchUpdate("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code) VALUES (1,?,?)", customerTags);
        jdbc.batchUpdate("INSERT INTO crm_customer_identity (tenant_id,customer_id,identity_type,source_app,"
                + "external_id,status) VALUES (1,?,'WECOM','ww-exp',?,'LINKED')", identities);
        jdbc.batchUpdate("INSERT INTO crm_customer_owner_history (tenant_id,customer_id,old_owner_id,new_owner_id,"
                + "changed_by,effective_at,reason) VALUES (1,?,10,11,10,?,'分配')", ownerHistories);
        jdbc.batchUpdate("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,contact_at,"
                + "summary,intent_project,next_followup_at,blocker_note,created_by,deleted,created_at) "
                + "VALUES (1,?,10,'WECHAT',?,'跟进','热玛吉',?,'预算',10,0,?)", followups);
        jdbc.batchUpdate("INSERT INTO crm_task (tenant_id,customer_id,assignee_user_id,task_team_id,task_type,"
                + "priority,status,title,due_at,source_type,source_ref,created_at) "
                + "VALUES (1,?,?,11,'FOLLOWUP','HIGH',?,'任务',?,'MANUAL',?,?)", tasks);
        jdbc.batchUpdate("INSERT INTO crm_order (id,tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,campaign_id,status,receiver_type,payable_fen,paid_fen,"
                + "refunded_fen,ordered_at,paid_at,created_at) "
                + "VALUES (?,1,?,?,10,10,'CRM','REGULAR',?,?, 'PLATFORM',10000,10000,0,?,?,?)", orders);
        jdbc.batchUpdate("INSERT INTO crm_order_item (id,tenant_id,order_id,sku_id,package_version_id,"
                + "sku_name_snapshot,purchase_quantity,list_amount_fen,paid_allocated_fen) "
                + "VALUES (?,1,?,?,1,'SKU',1,10000,10000)", orderItems);
        jdbc.batchUpdate("INSERT INTO crm_payment_txn (tenant_id,order_id,payment_channel,receiver_type,"
                + "external_trade_no,amount_fen,status,confirmed_at) "
                + "VALUES (1,?,'BANK','PLATFORM',?,10000,?,?)", payments);
        jdbc.batchUpdate("INSERT INTO crm_entitlement (id,tenant_id,customer_id,order_item_id,project_id,granted_count,"
                + "available_count,status) VALUES (?,1,?,?,?,2,2,'AVAILABLE')", entitlements);
        jdbc.batchUpdate("INSERT INTO crm_entitlement_ledger (tenant_id,entitlement_id,txn_type,delta_count,"
                + "source_type,source_ref,idempotency_key,actor_user_id) VALUES (1,?,?,?,?,?,?,10)",
                entitlementLedgers);
        jdbc.batchUpdate("INSERT INTO crm_appointment (id,tenant_id,appointment_no,customer_id,institution_id,"
                + "owner_staff_id,team_id,scheduled_at,status,confirmed_at,checked_in_at,"
                + "service_done_at,created_by,created_at) "
                + "VALUES (?,1,?,?,?,10,11,?,'SERVICE_DONE',?,?,?,10,?)", appointments);
        jdbc.batchUpdate("INSERT INTO crm_appointment_item (id,tenant_id,appointment_id,entitlement_id,project_id,"
                + "planned_count) VALUES (?,1,?,?,?,1)", appointmentItems);
        jdbc.batchUpdate("INSERT INTO crm_redemption (id,tenant_id,appointment_id,customer_id,institution_id,"
                + "owner_staff_id,team_id,service_at,evidence_ref,status,idempotency_key,posted_by,"
                + "posted_at,created_at) VALUES (?,1,?,?,?,10,11,?,?,'POSTED',?,10,?,?)", redemptions);
        jdbc.batchUpdate("INSERT INTO crm_redemption_line (tenant_id,redemption_id,appointment_item_id,entitlement_id,"
                + "project_id,count) VALUES (1,?,?,?,?,1)", redemptionLines);
        jdbc.batchUpdate("INSERT INTO crm_refund (id,tenant_id,refund_no,order_id,customer_id,requested_fen,"
                + "approved_fen,succeeded_fen,reason_code,status,requested_by,created_at) "
                + "VALUES (?,1,?,?,?,1000,1000,1000,'REASON',?,10,?)", refunds);
        jdbc.batchUpdate("INSERT INTO crm_refund_payment (tenant_id,refund_id,provider,external_refund_no,amount_fen,"
                + "status,verification_method,succeeded_at) VALUES (1,?,'YOUZAN',?,1000,?,'MANUAL_REVIEW',?)",
                refundPayments);
        jdbc.batchUpdate("INSERT INTO crm_customer_tier_history (tenant_id,customer_id,from_tier_code,to_tier_code,"
                + "change_type,actor_user_id,reason,effective_at,created_at) "
                + "VALUES (1,?,'B','A','MANUAL_ADJUST',10,'升级',?,?)", tierHistories);
        jdbc.batchUpdate("INSERT INTO crm_complaint_hit (id,tenant_id,customer_id,owner_staff_id_snapshot,"
                + "team_id_snapshot,source_type,source_id,keyword_id,keyword_snapshot,evidence_masked,"
                + "status,routing_status,recipient_user_id_snapshot,recipient_source_snapshot,"
                + "policy_version_snapshot) VALUES (?,1,?,10,11,'FOLLOWUP',?,1,'投诉关键词','脱敏',"
                + "'CONVERTED','ROUTED',10,'TEAM_LEADER',1)", complaintHits);
        jdbc.batchUpdate("INSERT INTO crm_complaint_case (id,tenant_id,complaint_case_no,complaint_hit_id,customer_id,"
                + "owner_staff_id_snapshot,team_id_snapshot,recipient_user_id_snapshot,"
                + "recipient_source_snapshot,status,created_by,created_at) "
                + "VALUES (?,1,?,?,?,10,11,10,'TEAM_LEADER','OPEN',10,?)", complaintCases);
        jdbc.batchUpdate("INSERT INTO crm_youzan_event (id,tenant_id,shop_id,event_id,event_type,external_order_no,"
                + "external_customer_id,campaign_id,payload_masked_json,payload_cipher,source_system,"
                + "process_status,received_at,order_id) "
                + "VALUES (?,1,?,?, 'ORDER_PAID',?,?,?,'{}','cipher','WEBHOOK','PROCESSED',?,?)", youzanEvents);
        jdbc.batchUpdate("INSERT INTO crm_youzan_order_map (tenant_id,shop_id,external_order_no,crm_order_id,"
                + "last_external_updated_at) VALUES (1,?,?,?,?)", youzanOrderMaps);
        jdbc.batchUpdate("INSERT INTO crm_youzan_customer_link (tenant_id,shop_id,external_customer_id,customer_id,"
                + "link_type,confidence,reason,created_by) VALUES (1,?,?,?,'MANUAL','HIGH','ok',10)",
                youzanCustomerLinks);
        jdbc.batchUpdate("INSERT INTO crm_youzan_order_latest (tenant_id,shop_id,external_order_no,latest_event_id) "
                + "VALUES (1,?,?,?)", youzanOrderLatests);
        jdbc.batchUpdate("INSERT INTO crm_youzan_sync_issue (id,tenant_id,event_id,issue_type,status,detail_masked,"
                + "attempts) VALUES (?,1,?,'CUSTOMER_UNRESOLVED','OPEN','err',0)", youzanSyncIssues);
        jdbc.batchUpdate("INSERT INTO crm_risk_case (id,tenant_id,risk_case_no,risk_type,severity,source_type,"
                + "source_ref,customer_id,order_id,status) VALUES (?,1,?,?,'HIGH',?,?,?,?,'OPEN')", riskCases);
        jdbc.batchUpdate("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                + "consent_status) VALUES (1,'ww-exp',?,10,?,'GRANTED')", wecomContacts);
        jdbc.batchUpdate("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_seq,customer_id,"
                + "external_userid,staff_user_id,conversation_type,conversation_id,message_type,"
                + "sender_type,sent_at,content_object_key,content_sha256,consent_snapshot,status) "
                + "VALUES (1,'ww-exp',?,?,?,?,10,'DIRECT',?,'TEXT','CUSTOMER',?,?,?,'GRANTED','STORED')",
                wecomMessages);
        jdbc.batchUpdate("INSERT INTO crm_material (id,tenant_id,material_no,title,category_code,review_status,"
                + "created_by) VALUES (?,1,?,?,'POSTER','APPROVED',10)", materials);
        jdbc.batchUpdate("INSERT INTO crm_material_revision (id,tenant_id,material_id,revision_no,title,category_code,"
                + "tags_json,file_id,status,created_by) VALUES (?,1,?,?,?,'POSTER',?,901,?,10)", materialRevisions);
        jdbc.batchUpdate("UPDATE crm_material SET latest_revision_id=?, current_revision_id=? WHERE id=?",
                materialUpdates);
        jdbc.batchUpdate("INSERT INTO crm_material_tag (tenant_id,material_id,tag_code) VALUES (1,?,?)",
                materialTags);
        jdbc.batchUpdate("INSERT INTO crm_profile_change_request (tenant_id,institution_id,change_type,target_id,"
                + "base_version,target_version,reason,status,current_step,requested_by,"
                + "requested_at,active_target) "
                + "VALUES (1,?,'PROFILE_FIELD',?,0,0,'测试变更',?,'MARKET',10,?,?)", profileChanges);
        jdbc.batchUpdate("INSERT INTO crm_notification (tenant_id,recipient_user_id,notification_type,title,"
                + "content_masked,source_type,source_id,dedupe_key,channels,created_at,read_at) "
                + "VALUES (1,?,'TASK_DUE','标题','内容','TASK',?,?,'IN_APP',?,?)", notifications);
        jdbc.batchUpdate("INSERT INTO crm_audit_log (tenant_id,actor_user_id,action_code,target_type,target_id) "
                + "VALUES (1,10,?,'ORDER',?)", auditLogs);
    }

    private static MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        ds.setUser("root");
        ds.setPassword(MYSQL.getPassword());
        return ds;
    }
}
