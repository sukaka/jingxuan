package com.whale.crm.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FollowupAutomationApiIT extends AbstractMysqlIT {
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void policyComputesOnlyNewFollowupsAndCreateIsIdempotentWithMinimalEvent() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        String policy = mockMvc.perform(get("/api/v1/followup-automation/policy").cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultIntervalDays").value(14))
                .andReturn().getResponse().getContentAsString();
        long version = objectMapper.readTree(policy).at("/data/version").asLong();
        String updated = mockMvc.perform(admin.withAuth(put("/api/v1/followup-automation/policy")).header("Idempotency-Key", "ita-policy-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultIntervalDays\":14,\"intentOverrides\":"
                                + "[{\"intentLevel\":\"HIGH\",\"intervalDays\":3}],\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.intentOverrides[0].intentLevel").value("HIGH"))
                .andReturn().getResponse().getContentAsString();
        long updatedVersion = objectMapper.readTree(updated).at("/data/version").asLong();
        mockMvc.perform(admin.withAuth(put("/api/v1/followup-automation/policy")).header("Idempotency-Key", "ita-policy-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"defaultIntervalDays\":20,\"intentOverrides\":[],\"version\":"
                                + (updatedVersion + 1) + "}"))
                .andExpect(status().isConflict());

        long highCustomer = createCustomer(manager, "ITA-高意向");
        jdbcTemplate.update("UPDATE crm_customer SET intent_level='HIGH' WHERE tenant_id=1 AND id=?", highCustomer);
        String request = "{\"customerId\":" + highCustomer + ",\"channelCode\":\"WECOM\","
                + "\"contactAt\":\"2026-01-10T00:00:00Z\",\"summary\":\"不进入事件正文\"}";
        String first = createFollowup(manager, request, "ita-policy-high")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nextFollowupAt").value("2026-01-13T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        long followupId = objectMapper.readTree(first).at("/data/id").asLong();
        String replay = createFollowup(manager, request, "ita-policy-high")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).at("/data/id").asLong()).isEqualTo(followupId);
        createFollowup(manager, request.replace("不进入事件正文", "不同请求"), "ita-policy-high")
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_followup WHERE tenant_id=1 AND customer_id=?",
                Long.class, highCustomer)).isEqualTo(1);

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload_json FROM crm_outbox_event WHERE tenant_id=1 AND event_type='followup.recorded.v1' "
                        + "AND aggregate_type='followup' AND aggregate_id=?", String.class, Long.toString(followupId));
        JsonNode event = objectMapper.readTree(payload);
        assertThat(event.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("followupId", "customerId", "sourceVersion", "staffUserId", "contactAt");
        assertThat(event.at("/followupId").asLong()).isEqualTo(followupId);
        assertThat(event.at("/customerId").asLong()).isEqualTo(highCustomer);
        assertThat(event.at("/staffUserId").asLong()).isEqualTo(2);
        assertThat(event.at("/contactAt").asText()).isEqualTo("2026-01-10T00:00:00Z");
        assertThat(payload).doesNotContain("不进入事件正文");

        long defaultCustomer = createCustomer(manager, "ITA-默认间隔");
        createFollowup(manager, "{\"customerId\":" + defaultCustomer + ",\"channelCode\":\"PHONE\","
                        + "\"contactAt\":\"2026-01-10T00:00:00Z\"}", "ita-policy-default")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nextFollowupAt").value("2026-01-24T00:00:00Z"));
        createFollowup(manager, "{\"customerId\":" + defaultCustomer + ",\"channelCode\":\"PHONE\","
                        + "\"contactAt\":\"2026-01-10T00:00:00Z\","
                        + "\"nextFollowupAt\":\"2026-02-01T02:00:00Z\"}", "ita-policy-explicit")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.nextFollowupAt").value("2026-02-01T02:00:00Z"));
    }

    @Test
    void monthlyBatchPreviewsThenExecutesOneTaskForMultipleOrdersAndReplays() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        AuthCookies otherManager = login("manager2");
        long customerId = createCustomer(manager, "ITA-月度批次");
        long firstOrder = insertOrder(customerId, "PAID", "2026-08-15T02:00:00Z");
        long secondOrder = insertOrder(customerId, "PENDING_PAYMENT", "2026-08-20T02:00:00Z");
        insertOrder(customerId, "DRAFT", "2026-08-16T02:00:00Z");
        insertOrder(customerId, "CANCELED", "2026-08-17T02:00:00Z");

        mockMvc.perform(get("/api/v1/followup-automation/batches/preview")
                        .param("orderMonth", "2026-08").param("page", "1").param("size", "20")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatchMonth").value("2026-11"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId))
                .andExpect(jsonPath("$.data.items[0].orderIds.length()").value(2));
        mockMvc.perform(get("/api/v1/followup-automation/batches/preview")
                        .param("orderMonth", "2026-08").cookie(otherManager.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        String created = createBatch(admin, "2026-08", "ita-batch-create-202608")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn().getResponse().getContentAsString();
        long batchId = objectMapper.readTree(created).at("/data/id").asLong();
        long version = objectMapper.readTree(created).at("/data/version").asLong();
        String replay = createBatch(admin, "2026-08", "ita-batch-create-202608")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).at("/data/id").asLong()).isEqualTo(batchId);

        mockMvc.perform(admin.withAuth(post("/api/v1/followup-automation/batches/" + batchId + "/execute"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isBadRequest());
        String executed = action(admin, post("/api/v1/followup-automation/batches/" + batchId + "/execute"),
                "{\"version\":" + version + "}", "ita-batch-execute-202608")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(executed).at("/data/items/0/taskId").asLong();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_followup_batch_order WHERE tenant_id=1 AND batch_id=?",
                Long.class, batchId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_followup_batch_order WHERE tenant_id=1 AND batch_id=? AND order_id IN (?,?)",
                Long.class, batchId, firstOrder, secondOrder)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT assignee_user_id FROM crm_task WHERE tenant_id=1 AND id=?",
                Long.class, taskId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_notification WHERE tenant_id=1 AND notification_type='FOLLOWUP_BATCH_ASSIGNED' "
                        + "AND source_id=? AND channels='IN_APP,WECOM_APP'", Long.class, Long.toString(taskId))).isEqualTo(1);

        action(admin, post("/api/v1/followup-automation/batches/" + batchId + "/execute"),
                "{\"version\":" + version + "}", "ita-batch-execute-202608")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value(taskId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_task WHERE tenant_id=1 AND source_type='MONTHLY_BATCH' AND customer_id=?",
                Long.class, customerId)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/followup-automation/batches/" + batchId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderIds.length()").value(2));
    }

    @Test
    void inactiveOwnerUsesCustomerTeamPoolAndOpenAutomaticTaskDedupesAcrossBatches() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        long customerId = createCustomer(manager, "ITA-失效负责人");
        String username = "ita_disabled_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status)
                SELECT tenant_id,org_id,team_id,?,'合成停用管家',password_hash,'EMPLOYEE','DISABLED'
                FROM sys_user WHERE tenant_id=1 AND id=2
                """, username);
        Long disabledOwner = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", Long.class, username);
        jdbcTemplate.update("UPDATE crm_customer SET owner_staff_id=?,team_id=1 WHERE tenant_id=1 AND id=?",
                disabledOwner, customerId);
        insertOrder(customerId, "PAID", "2026-07-10T02:00:00Z");
        insertOrder(customerId, "PAID", "2026-06-10T02:00:00Z");

        long firstBatch = batchId(createBatch(admin, "2026-07", "ita-pool-create-202607")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String first = action(admin, post("/api/v1/followup-automation/batches/" + firstBatch + "/execute"),
                "{\"version\":0}", "ita-pool-execute-202607")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(first).at("/data/items/0/taskId").asLong();
        var task = jdbcTemplate.queryForMap("SELECT * FROM crm_task WHERE tenant_id=1 AND id=?", taskId);
        assertThat(task.get("assignee_user_id")).isNull();
        assertThat(task.get("original_assignee_user_id")).isNull();
        assertThat(((Number) task.get("task_team_id")).longValue()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT owner_staff_id FROM crm_customer WHERE tenant_id=1 AND id=?",
                Long.class, customerId)).isEqualTo(disabledOwner);

        long secondBatch = batchId(createBatch(admin, "2026-06", "ita-pool-create-202606")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        action(admin, post("/api/v1/followup-automation/batches/" + secondBatch + "/execute"),
                "{\"version\":0}", "ita-pool-execute-202606")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.items[0].taskId").value(taskId));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_task WHERE tenant_id=1 AND source_type='MONTHLY_BATCH' AND customer_id=?",
                Long.class, customerId)).isEqualTo(1);
    }

    @Test
    void failedBatchReleasesLeaseAndRetryAppendsAttemptWithoutDuplicatingTask() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager = login("manager1");
        long customerId = createCustomer(manager, "ITA-失败重试");
        String username = "ita_retry_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO sys_user (tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status)
                SELECT tenant_id,org_id,team_id,?,'合成重试停用管家',password_hash,'EMPLOYEE','DISABLED'
                FROM sys_user WHERE tenant_id=1 AND id=2
                """, username);
        Long disabledOwner = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=1 AND username=?", Long.class, username);
        jdbcTemplate.update("UPDATE crm_customer SET owner_staff_id=?,team_id=NULL WHERE tenant_id=1 AND id=?",
                disabledOwner, customerId);
        insertOrder(customerId, "PAID", "2026-05-10T02:00:00Z");
        long batchId = batchId(createBatch(admin, "2026-05", "ita-retry-create-202605")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        String failed = action(admin, post("/api/v1/followup-automation/batches/" + batchId + "/execute"),
                "{\"version\":0}", "ita-retry-execute-202605")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        long failedVersion = objectMapper.readTree(failed).at("/data/version").asLong();
        var failedBatch = jdbcTemplate.queryForMap("SELECT * FROM crm_followup_batch WHERE tenant_id=1 AND id=?", batchId);
        assertThat(failedBatch.get("lease_owner")).isNull();
        assertThat(failedBatch.get("next_retry_at")).isNotNull();

        jdbcTemplate.update("UPDATE crm_customer SET team_id=1 WHERE tenant_id=1 AND id=?", customerId);
        action(admin, post("/api/v1/followup-automation/batches/" + batchId + "/retry"),
                "{\"version\":" + failedVersion + "}", "ita-retry-retry-202605")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.retryCount").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("CREATED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_followup_batch_attempt WHERE tenant_id=1 AND batch_id=?",
                Long.class, batchId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_task WHERE tenant_id=1 AND source_type='MONTHLY_BATCH' AND customer_id=?",
                Long.class, customerId)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions createFollowup(
            AuthCookies actor, String body, String key) throws Exception {
        return action(actor, post("/api/v1/followups"), body, key);
    }

    private org.springframework.test.web.servlet.ResultActions createBatch(
            AuthCookies actor, String month, String key) throws Exception {
        return action(actor, post("/api/v1/followup-automation/batches"),
                "{\"orderMonth\":\"" + month + "\"}", key);
    }

    private org.springframework.test.web.servlet.ResultActions action(
            AuthCookies actor, MockHttpServletRequestBuilder builder, String body, String key) throws Exception {
        return mockMvc.perform(actor.withAuth(builder).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long batchId(String response) throws Exception {
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    private long createCustomer(AuthCookies actor, String nickname) throws Exception {
        String body = mockMvc.perform(actor.withAuth(post("/api/v1/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/id").asLong();
    }

    private long insertOrder(long customerId, String status, String orderedAt) {
        String orderNo = "ITA-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,
                    source_system,order_type_code,status,receiver_type,ordered_at)
                VALUES (1,?,?,2,2,'CRM','REGULAR',?,'PLATFORM',?)
                """, orderNo, customerId, status, Timestamp.from(Instant.parse(orderedAt)));
        Long id = jdbcTemplate.queryForObject("SELECT id FROM crm_order WHERE tenant_id=1 AND order_no=?",
                Long.class, orderNo);
        return id == null ? 0 : id;
    }
}
