package com.whale.crm.integration.wecom.tag;

import com.fasterxml.jackson.databind.JsonNode;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.application.WecomCallbackService;
import com.whale.crm.integration.wecom.tag.adapter.MockWecomTagClient;
import com.whale.crm.integration.wecom.tag.adapter.WecomCorpTag;
import com.whale.crm.integration.wecom.tag.application.WecomTagSyncService;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 企微企业标签双向同步集成测试（V63）：
 * 覆盖 8 个接口 RBAC、标签库双向同步与幂等、手工解绑/绑定、WeCom→CRM 零回声回流、
 * CRM→WeCom 异步入队/重试/脱敏出参、change_external_tag 回调拉取，以及 V63 迁移与回滚验证。
 */
@SpringBootTest(properties = {"app.files.enabled=false", "app.outbox.relay.enabled=false",
        "app.scheduling.enabled=false", "app.wecom.official.callback-delay-ms=600000"})
class WecomTagSyncApiIT extends AbstractMysqlIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockWecomTagClient mockWecomTagClient;
    @Autowired
    private WecomTagSyncService tagSyncService;
    @Autowired
    private WecomCallbackService callbackService;

    @BeforeEach
    void setUp() {
        cleanTestState();
        jdbcTemplate.update(
                "INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                        + "VALUES (1,?,'staff-2',2,1),(1,?,'staff-3',3,1) ON DUPLICATE KEY UPDATE enabled=1",
                MockWecomAdapter.CORP_ID, MockWecomAdapter.CORP_ID);
        jdbcTemplate.update(
                "UPDATE crm_wecom_contact SET customer_id=1010, follow_status='ACTIVE', "
                        + "tag_snapshot_json='[{\"group_name\":\"虚构分组\",\"tag_name\":\"企微客户\",\"tag_id\":\"mock-tag\",\"type\":1}]' "
                        + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2",
                MockWecomAdapter.CORP_ID);
    }

    @AfterEach
    void tearDown() {
        cleanTestState();
    }

    @Test
    void rbacEnforcesRoleBoundariesAcrossAllEightEndpoints() throws Exception {
        AuthCookies finance = login("finance1");
        AuthCookies manager = login("manager1");
        AuthCookies lead = login("lead1");

        // 财务无 customer:tag:manage / wecom:read / customer:write → 全部 403
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/mappings").cookie(finance.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(finance.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/VIP"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLINK\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(finance.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-fin-cat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(finance.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                        .header("Idempotency-Key", "it-fin-cust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(finance.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1010/push")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/runs").cookie(finance.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks").cookie(finance.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(finance.withAuth(post("/api/v1/integrations/wecom/tag-sync/tasks/1/retry")))
                .andExpect(status().isForbidden());

        // 普通管家有 wecom:read + customer:write，无 customer:tag:manage / wecom:monitor
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/mappings").cookie(manager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.adapterName").value(MockWecomTagClient.ADAPTER_NAME))
                .andExpect(jsonPath("$.data.officialEnabled").value(false));
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/runs").cookie(manager.cookies()))
                .andExpect(status().isOk());
        mockMvc.perform(manager.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/VIP"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLINK\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-mgr-cat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                        .header("Idempotency-Key", "it-mgr-cust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks").cookie(manager.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/tasks/1/retry")))
                .andExpect(status().isForbidden());
        // 本人客户 1010 允许单客推送，他人客户 1003 越权 404
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1010/push")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(1010));
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1003/push")))
                .andExpect(status().isNotFound());

        // 机构账号（INSTITUTION_STAFF）无权限 → 全部 8 个接口 403
        AuthCookies inst = login("inst1");
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/mappings").cookie(inst.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(inst.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/VIP"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLINK\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(inst.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-inst-cat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(inst.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                        .header("Idempotency-Key", "it-inst-cust-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(inst.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1010/push")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/runs").cookie(inst.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks").cookie(inst.cookies()))
                .andExpect(status().isForbidden());
        mockMvc.perform(inst.withAuth(post("/api/v1/integrations/wecom/tag-sync/tasks/1/retry")))
                .andExpect(status().isForbidden());

        // 管家主管具备 customer:tag:manage + wecom:monitor，但单客推送仍受 TEAM=1 DataScope 限制（跨组客户 1003 → 404）
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1003/push")))
                .andExpect(status().isNotFound());
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-lead-cat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks").cookie(lead.cookies()))
                .andExpect(status().isOk());
    }

    @Test
    void catalogBidirectionalSyncIdempotencyManualMappingAndNonDestructiveUnlink() throws Exception {
        AuthCookies lead = login("lead1");

        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/mappings").cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.crmTagCode == 'VIP')].syncStatus", hasItem("UNMAPPED")));

        String key = "it-cat-bidir-" + UUID.randomUUID();
        String runBody = mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"BIDIRECTIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncScope").value("CATALOG"))
                .andExpect(jsonPath("$.data.direction").value("BIDIRECTIONAL"))
                .andExpect(jsonPath("$.data.adapterName").value(MockWecomTagClient.ADAPTER_NAME))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        long runId = objectMapper.readTree(runBody).at("/data/id").asLong();
        assertThat(objectMapper.readTree(runBody).at("/data/pulledCount").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(objectMapper.readTree(runBody).at("/data/pushedCount").asInt()).isGreaterThanOrEqualTo(3);

        // 幂等重放返回同一 runId；同键不同方向 → 409
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"BIDIRECTIONAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(runId));
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isConflict());

        // 企微端删除 mock-tag 后再 PULL：仅将映射置 UNLINKED，严禁删除 CRM 字典标签
        String wecomCustomerCrmCode = jdbcTemplate.queryForObject(
                "SELECT crm_tag_code FROM crm_wecom_tag_mapping WHERE tenant_id=1 AND corp_id=? AND wecom_tag_id=?",
                String.class, MockWecomAdapter.CORP_ID, MockWecomTagClient.SEED_TAG_WECOM_CUSTOMER);
        assertThat(wecomCustomerCrmCode).isNotBlank();

        mockWecomTagClient.deleteCorpTag(MockWecomTagClient.SEED_TAG_WECOM_CUSTOMER);
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-cat-pull-del-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        String statusAfterDelete = jdbcTemplate.queryForObject(
                "SELECT sync_status FROM crm_wecom_tag_mapping WHERE tenant_id=1 AND corp_id=? AND crm_tag_code=?",
                String.class, MockWecomAdapter.CORP_ID, wecomCustomerCrmCode);
        assertThat(statusAfterDelete).isEqualTo("UNLINKED");
        String dictStatus = jdbcTemplate.queryForObject(
                "SELECT i.status FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                        + "WHERE i.tenant_id=1 AND t.dict_code='customerTags' AND i.option_code=?",
                String.class, wecomCustomerCrmCode);
        assertThat(dictStatus).isEqualTo("ACTIVE");

        // 手工解除映射与重新绑定 + 审计落库
        mockMvc.perform(lead.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/VIP"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"UNLINK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("UNLINKED"));
        mockMvc.perform(lead.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/VIP"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"BIND\",\"wecomGroupId\":\"mock-grp-1\",\"wecomGroupName\":\"虚构分组\","
                                + "\"wecomTagId\":\"mock-tag-vip\",\"wecomTagName\":\"贵宾客户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.syncStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.wecomTagId").value("mock-tag-vip"));

        Integer catalogAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='WECOM_TAG_CATALOG_SYNC'",
                Integer.class);
        Integer mappingAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='WECOM_TAG_MAPPING_UPDATE' "
                        + "AND target_id='VIP'", Integer.class);
        assertThat(catalogAudits).isGreaterThanOrEqualTo(2);
        assertThat(mappingAudits).isGreaterThanOrEqualTo(2);
    }

    @Test
    void customerTagBidirectionalSyncHasZeroFeedbackLoopSupportsAsyncPushRetryAndCallback() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");

        // 1. 先执行标签库双向同步，并把 NEW_7D 解绑作为「仅 CRM 本地未映射标签」
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-cust-init-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"BIDIRECTIONAL\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.withAuth(put("/api/v1/integrations/wecom/tag-sync/mappings/NEW_7D"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"UNLINK\"}"))
                .andExpect(status().isOk());
        String wecomCustomerCrmCode = jdbcTemplate.queryForObject(
                "SELECT crm_tag_code FROM crm_wecom_tag_mapping WHERE tenant_id=1 AND corp_id=? AND wecom_tag_id=?",
                String.class, MockWecomAdapter.CORP_ID, MockWecomTagClient.SEED_TAG_WECOM_CUSTOMER);
        assertThat(wecomCustomerCrmCode).isNotBlank();

        // 先给客户 1010 贴上未映射本地标签 NEW_7D
        jdbcTemplate.update("INSERT IGNORE INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,1010,'NEW_7D',2)");

        // 2. WeCom → CRM 批量拉取：客户 1010 获得企微映射标签，同时完整保留未映射本地标签 NEW_7D，且产生 0 条反向推送任务
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                        .header("Idempotency-Key", "it-cust-pull-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.pulledCount").value(1));

        List<String> tagsAfterPull = jdbcTemplate.queryForList(
                "SELECT tag_code FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1010 ORDER BY tag_code",
                String.class);
        assertThat(tagsAfterPull).contains("NEW_7D", wecomCustomerCrmCode);

        Integer tasksAfterPull = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_wecom_customer_tag_push_task WHERE tenant_id=1 AND customer_id=1010",
                Integer.class);
        assertThat(tasksAfterPull).isZero();
        Integer pullAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='WECOM_CUSTOMER_TAG_PULL' AND target_id='1010'",
                Integer.class);
        assertThat(pullAudits).isGreaterThanOrEqualTo(1);

        // 3. CRM → WeCom：管家修改客户 1010 标签（新增 VIP），事务内自动入队异步推送任务
        mockMvc.perform(manager.withAuth(put("/api/v1/customers/1010/tags"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagCodes\":[\"NEW_7D\",\"" + wecomCustomerCrmCode + "\",\"VIP\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes", hasItem("VIP")));

        String tasksBeforeWorker = mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks")
                        .param("status", "PENDING")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customerId").value(1010))
                .andExpect(jsonPath("$.data[0].addTagCount").value(1))
                .andExpect(jsonPath("$.data[0].removeTagCount").value(0))
                .andReturn().getResponse().getContentAsString();
        String maskedExt = objectMapper.readTree(tasksBeforeWorker).at("/data/0/externalUseridMasked").asText();
        assertThat(maskedExt).contains("***").doesNotContain("WECOM-EXT-1010");

        int processed = tagSyncService.processPendingPushTasks();
        assertThat(processed).isEqualTo(1);
        assertThat(mockWecomTagClient.customerTagIds("staff-2", "WECOM-EXT-1010"))
                .contains(MockWecomTagClient.SEED_TAG_VIP);
        Integer pushAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 AND action_code='WECOM_CUSTOMER_TAG_PUSH' AND target_id='1010'",
                Integer.class);
        assertThat(pushAudits).isGreaterThanOrEqualTo(1);

        // 4. 单客手动推送失败进入 RETRYABLE，随后手工重试成功
        jdbcTemplate.update(
                "UPDATE crm_wecom_contact SET tag_snapshot_json='[\"mock-tag\"]' "
                        + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2",
                MockWecomAdapter.CORP_ID);
        mockWecomTagClient.failNextMark();
        mockMvc.perform(manager.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers/1010/push")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedContacts").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.errorMasked").isNotEmpty());

        String retryableTasksBody = mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks")
                        .param("status", "RETRYABLE")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode retryTask = objectMapper.readTree(retryableTasksBody).at("/data/0");
        long failedTaskId = retryTask.at("/id").asLong();

        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/tag-sync/tasks/" + failedTaskId + "/retry")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        // 5. 已停用 CRM 标签在企微回流时绝不新写入客户，且已有客户历史停用标签不被误删（D01 非破坏原则）
        Long highIntentItemId = jdbcTemplate.queryForObject(
                "SELECT i.id FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                        + "WHERE i.tenant_id=1 AND t.dict_code='customerTags' AND i.option_code='HIGH_INTENT'",
                Long.class);
        assertThat(highIntentItemId).isNotNull();
        try {
            // 客户 1003 停用前已持有 HIGH_INTENT，但其企微快照不含 mock-tag-high；客户 1010 未持有但快照含 mock-tag-high
            jdbcTemplate.update(
                    "INSERT IGNORE INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) VALUES (1,1003,'HIGH_INTENT',3)");
            jdbcTemplate.update(
                    "INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                            + "consent_status,follow_status,tag_snapshot_json) "
                            + "VALUES (1,?,'WECOM-EXT-1003-IT',3,1003,'GRANTED','ACTIVE','[\"mock-tag\"]') "
                            + "ON DUPLICATE KEY UPDATE customer_id=1003, follow_status='ACTIVE', "
                            + "tag_snapshot_json='[\"mock-tag\"]'",
                    MockWecomAdapter.CORP_ID);
            mockMvc.perform(admin.withAuth(post("/api/v1/customer-tags/" + highIntentItemId + "/disable")))
                    .andExpect(status().isOk());
            jdbcTemplate.update(
                    "UPDATE crm_wecom_contact SET tag_snapshot_json='[\"mock-tag\",\"mock-tag-high\"]' "
                            + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2",
                    MockWecomAdapter.CORP_ID);
            mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                            .header("Idempotency-Key", "it-cust-pull-disabled-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"direction\":\"PULL\"}"))
                    .andExpect(status().isOk());
            List<String> tags1010AfterDisabledPull = jdbcTemplate.queryForList(
                    "SELECT tag_code FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1010",
                    String.class);
            assertThat(tags1010AfterDisabledPull).doesNotContain("HIGH_INTENT");
            List<String> tags1003AfterDisabledPull = jdbcTemplate.queryForList(
                    "SELECT tag_code FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1003",
                    String.class);
            assertThat(tags1003AfterDisabledPull).contains("HIGH_INTENT");
        } finally {
            jdbcTemplate.update("UPDATE sys_dictionary_item SET status='ACTIVE' WHERE tenant_id=1 AND id=?",
                    highIntentItemId);
            jdbcTemplate.update("DELETE FROM crm_wecom_contact WHERE tenant_id=1 AND external_userid='WECOM-EXT-1003-IT'");
            jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1003 AND tag_code='HIGH_INTENT'");
        }

        // 6. 企微 change_external_tag 回调自动拉取新企微标签至 CRM 字典并建立映射
        String callbackTagName = "回调新增-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        mockWecomTagClient.putRemoteTag(new WecomCorpTag(
                MockWecomTagClient.SEED_GROUP_ID, MockWecomTagClient.SEED_GROUP_NAME,
                "mock-tag-cb-1", callbackTagName, 30));
        callbackService.accept("<xml><CreateTime>1792991234</CreateTime>"
                + "<Event><![CDATA[change_external_tag]]></Event>"
                + "<ChangeType><![CDATA[create]]></ChangeType>"
                + "<Id><![CDATA[mock-tag-cb-1]]></Id><TagType><![CDATA[tag]]></TagType></xml>");
        callbackService.processPending();

        Integer cbMappingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_wecom_tag_mapping WHERE tenant_id=1 AND corp_id=? "
                        + "AND wecom_tag_id='mock-tag-cb-1' AND sync_status='ACTIVE'",
                Integer.class, MockWecomAdapter.CORP_ID);
        assertThat(cbMappingCount).isEqualTo(1);
    }

    @Test
    void teamDataScopeIsolatesTasksRetryAndCustomerSyncForManagerLead() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies lead = login("lead1");

        // 初始化标签库映射
        mockMvc.perform(admin.withAuth(post("/api/v1/integrations/wecom/tag-sync/catalog"))
                        .header("Idempotency-Key", "it-scope-cat-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"BIDIRECTIONAL\"}"))
                .andExpect(status().isOk());

        // 为团队1客户 1010 与跨组团队2客户 1003 均准备企微联系人快照与 RETRYABLE 推送任务
        jdbcTemplate.update(
                "UPDATE crm_wecom_contact SET tag_snapshot_json='[\"mock-tag-vip\"]' "
                        + "WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010' AND staff_user_id=2",
                MockWecomAdapter.CORP_ID);
        jdbcTemplate.update(
                "INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                        + "consent_status,follow_status,tag_snapshot_json) "
                        + "VALUES (1,?,'WECOM-EXT-1003-IT',3,1003,'GRANTED','ACTIVE','[\"mock-tag-vip\"]') "
                        + "ON DUPLICATE KEY UPDATE customer_id=1003, follow_status='ACTIVE', "
                        + "tag_snapshot_json='[\"mock-tag-vip\"]'",
                MockWecomAdapter.CORP_ID);
        Long contact1010Id = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1010'",
                Long.class, MockWecomAdapter.CORP_ID);
        Long contact1003Id = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_contact WHERE tenant_id=1 AND corp_id=? AND external_userid='WECOM-EXT-1003-IT'",
                Long.class, MockWecomAdapter.CORP_ID);
        assertThat(contact1010Id).isNotNull();
        assertThat(contact1003Id).isNotNull();

        jdbcTemplate.update(
                "INSERT INTO crm_wecom_customer_tag_push_task (tenant_id,corp_id,customer_id,wecom_contact_id,"
                        + "wecom_userid,external_userid,add_tag_ids_json,remove_tag_ids_json,status,attempts,"
                        + "max_attempts,error_masked,trigger_source,created_by) "
                        + "VALUES (1,?,1010,?,'staff-2','WECOM-EXT-1010','[\"mock-tag-vip\"]','[]','RETRYABLE',1,5,"
                        + "'mock-err','MANUAL',2),"
                        + "(1,?,1003,?,'staff-3','WECOM-EXT-1003-IT','[\"mock-tag-vip\"]','[]','RETRYABLE',1,5,"
                        + "'mock-err','MANUAL',3)",
                MockWecomAdapter.CORP_ID, contact1010Id, MockWecomAdapter.CORP_ID, contact1003Id);

        Long crossTeamTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_wecom_customer_tag_push_task WHERE tenant_id=1 AND customer_id=1003",
                Long.class);
        assertThat(crossTeamTaskId).isNotNull();

        // admin (ALL) 可见全部 2 条任务；lead1 (TEAM=1) 仅见本组 1010 任务，绝不泄露跨组 1003 任务
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks")
                        .param("status", "RETRYABLE")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/v1/integrations/wecom/tag-sync/tasks")
                        .param("status", "RETRYABLE")
                        .cookie(lead.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].customerId").value(1010));

        // lead1 越权重试跨组 1003 的推送任务 → 404，且任务保持 RETRYABLE 未被改动
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/tasks/" + crossTeamTaskId + "/retry")))
                .andExpect(status().isNotFound());
        String crossTaskStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM crm_wecom_customer_tag_push_task WHERE tenant_id=1 AND id=?",
                String.class, crossTeamTaskId);
        assertThat(crossTaskStatus).isEqualTo("RETRYABLE");

        // lead1 执行批量客户标签拉取：仅同步本组可见客户 1010，绝不修改跨组客户 1003
        mockMvc.perform(lead.withAuth(post("/api/v1/integrations/wecom/tag-sync/customers"))
                        .header("Idempotency-Key", "it-scope-pull-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"direction\":\"PULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.pulledCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(0));

        List<String> tags1010 = jdbcTemplate.queryForList(
                "SELECT tag_code FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1010",
                String.class);
        List<String> tags1003 = jdbcTemplate.queryForList(
                "SELECT tag_code FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1003",
                String.class);
        assertThat(tags1010).contains("VIP");
        assertThat(tags1003).doesNotContain("VIP");
    }

    @Test
    void v63MigrationAndRollback_cleanlyCreatesAndDropsWecomTagTables() throws Exception {
        String schema = "v63_wecom_tag_verify";
        MysqlDataSource rootDs = dataSourceFor("medical_crm");
        try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        MysqlDataSource ds = dataSourceFor(schema);
        try {
            Flyway.configure().dataSource(ds).schemas(schema)
                    .locations("classpath:db/migration").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_wecom_tag_mapping','crm_wecom_tag_sync_run',"
                            + "'crm_wecom_customer_tag_push_task')",
                    String.class, schema);
            assertThat(tables).containsExactlyInAnyOrder(
                    "crm_wecom_tag_mapping", "crm_wecom_tag_sync_run", "crm_wecom_customer_tag_push_task");

            List<String> indexes = jdbc.queryForList(
                    "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=? "
                            + "AND index_name IN ('uk_wecom_tag_map_crm','uk_wecom_tag_map_wecom',"
                            + "'idx_wecom_tag_map_status','uk_wecom_tag_sync_running',"
                            + "'idx_wecom_tag_sync_run_recent','idx_wecom_tag_push_poll',"
                            + "'idx_wecom_tag_push_tenant_customer','idx_wecom_tag_push_tenant_status')",
                    String.class, schema);
            assertThat(indexes).containsExactlyInAnyOrder(
                    "uk_wecom_tag_map_crm", "uk_wecom_tag_map_wecom", "idx_wecom_tag_map_status",
                    "uk_wecom_tag_sync_running", "idx_wecom_tag_sync_run_recent",
                    "idx_wecom_tag_push_poll", "idx_wecom_tag_push_tenant_customer",
                    "idx_wecom_tag_push_tenant_status");

            try (Connection conn = ds.getConnection()) {
                ScriptUtils.executeSqlScript(conn,
                        new ClassPathResource("db/rollback/V63__wecom_tag_bidirectional_sync_rollback.sql"));
            }

            Long remainingTables = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name IN ('crm_wecom_tag_mapping','crm_wecom_tag_sync_run',"
                            + "'crm_wecom_customer_tag_push_task')",
                    Long.class, schema);
            assertThat(remainingTables).isZero();

            Long crmCustomerTagExists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? "
                            + "AND table_name='crm_customer_tag'",
                    Long.class, schema);
            assertThat(crmCustomerTagExists).isEqualTo(1L);
        } finally {
            try (Connection conn = rootDs.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    private void cleanTestState() {
        mockWecomTagClient.reset();
        jdbcTemplate.update("DELETE FROM crm_wecom_customer_tag_push_task WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_tag_sync_run WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_tag_mapping WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_callback_event WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_wecom_contact WHERE tenant_id=1 AND external_userid='WECOM-EXT-1003-IT'");
        jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1010");
        jdbcTemplate.update("DELETE FROM crm_customer_tag WHERE tenant_id=1 AND customer_id=1003 "
                + "AND tag_code <> 'REFERRAL_GOOD'");
        jdbcTemplate.update("INSERT IGNORE INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) "
                + "VALUES (1,1003,'REFERRAL_GOOD',3)");
        jdbcTemplate.update("DELETE i FROM sys_dictionary_item i "
                + "JOIN sys_dictionary_type t ON t.id=i.dict_type_id AND t.tenant_id=i.tenant_id "
                + "WHERE i.tenant_id=1 AND t.dict_code='customerTags' AND i.option_code LIKE 'TAG_%'");
    }

    private MysqlDataSource dataSourceFor(String database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl().replace("/medical_crm?", "/" + database + "?"));
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
