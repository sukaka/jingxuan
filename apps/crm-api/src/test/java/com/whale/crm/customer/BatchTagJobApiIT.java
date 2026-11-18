package com.whale.crm.customer;

import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BatchTagJobApiIT extends AbstractMysqlIT {
    private static final String ADD = "ADD";
    private static final String REMOVE = "REMOVE";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @BeforeEach
    void tagDictionaries() {
        tag("V34_VIP", "V34贵宾", "ACTIVE");
        tag("V34_REMOVE", "V34待移除", "ACTIVE");
        tag("VIP", "贵宾客户", "ACTIVE");
        tag("HIGH_INTENT", "高意向", "ACTIVE");
        tag("RISK", "风险客户", "ACTIVE");
    }

    @Test
    void filterPreviewCreatesIncrementalAddWithCsrfIdempotencyAuditAndUnknownHistory() throws Exception {
        AuthCookies admin = login("admin");
        String city = city("add");
        long customerId = customer(admin, "V34-add", city);
        jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) "
                + "VALUES (1,?,'LEGACY_UNKNOWN',1)", customerId);
        Map<String, Object> previewRequest = filterRequest(ADD, List.of("V34_VIP"), city);
        String payload = json(previewRequest);

        mockMvc.perform(post("/api/v1/batch-tag-jobs/preview").cookie(admin.cookies())
                        .header("Idempotency-Key", "v34-preview-no-csrf")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));

        String previewBody = mockMvc.perform(admin.withAuth(post("/api/v1/batch-tag-jobs/preview"))
                        .header("Idempotency-Key", "v34-add-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].customerId").value(customerId))
                .andReturn().getResponse().getContentAsString();
        String previewId = objectMapper.readTree(previewBody).at("/data/previewId").asText();

        String created = createJob(admin, previewId, "v34-add-create")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.job.status").value("PENDING"))
                .andExpect(jsonPath("$.data.job.requestedBy").value(1))
                .andExpect(jsonPath("$.data.job.idempotencyKey").value("v34-add-create"))
                .andExpect(jsonPath("$.data.job.scopeSnapshot.type").value("ALL"))
                .andExpect(jsonPath("$.data.items.total").value(1))
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(created).at("/data/job/id").asLong();
        String replay = createJob(admin, previewId, "v34-add-create")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(replay).at("/data/job/id").asLong()).isEqualTo(jobId);
        createJob(admin, previewId, "v34-add-create")
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_batch_tag_job WHERE preview_id=?",
                Long.class, previewId)).isEqualTo(1);

        String executed = action(admin, jobId, "execute", 0, "v34-add-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.job.succeededCount").value(1))
                .andExpect(jsonPath("$.data.items.items[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.items.items[0].changedTagCodes[0]").value("V34_VIP"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(executed).at("/data/items/items/0/afterTagCodes").toString())
                .contains("LEGACY_UNKNOWN").contains("V34_VIP");
        long version = objectMapper.readTree(executed).at("/data/job/version").asLong();
        long auditCount = auditCount(jobId);

        action(admin, jobId, "execute", 0, "v34-add-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("SUCCEEDED"));
        assertThat(auditCount(jobId)).isEqualTo(auditCount);
        action(admin, jobId, "execute", version, "v34-add-execute-new-key")
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/batch-tag-jobs/{id}", jobId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.sourceSnapshot.filters.cityCodes[0]").value(city));
        String listed = mockMvc.perform(get("/api/v1/batch-tag-jobs?status=SUCCEEDED&page=1&size=100")
                        .cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(listed).at("/data/items").toString()).contains("\"id\":" + jobId);
    }

    @Test
    void segmentOperationBatchIsFixedSnapshotAndRemoveHasIndependentRollback() throws Exception {
        AuthCookies admin = login("admin");
        String city = city("rollback");
        long customerId = customer(admin, "V34-rollback", city);
        jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) "
                + "VALUES (1,?,'LEGACY_UNKNOWN',1)", customerId);
        tagOnCustomer(admin, customerId, "V34_REMOVE", "LEGACY_UNKNOWN");

        String segmentBody = mockMvc.perform(admin.withAuth(post("/api/v1/customer-segments"))
                        .header("Idempotency-Key", key("segment"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "V34-" + city, "filters", Map.of("cityCodes", List.of(city))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long segmentId = objectMapper.readTree(segmentBody).at("/data/id").asLong();
        String batchBody = mockMvc.perform(admin.withAuth(post(
                                "/api/v1/customer-segments/{id}/operation-batches", segmentId))
                        .header("Idempotency-Key", key("segment-batch"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("operationType", "FOLLOWUP", "version", 0))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long operationBatchId = objectMapper.readTree(batchBody).at("/data/id").asLong();

        String previewBody = preview(admin, Map.of("operation", REMOVE, "tagCodes", List.of("V34_REMOVE"),
                        "source", Map.of("type", "OPERATION_BATCH", "id", operationBatchId),
                        "page", 1, "size", 20), "v34-remove-preview")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn().getResponse().getContentAsString();
        String previewId = objectMapper.readTree(previewBody).at("/data/previewId").asText();
        jdbcTemplate.update("UPDATE crm_customer SET city_code='BJ' WHERE id=?", customerId);

        String created = createJob(admin, previewId, "v34-remove-create")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(created).at("/data/job/id").asLong();
        String executed = action(admin, jobId, "execute", 0, "v34-remove-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        long version = objectMapper.readTree(executed).at("/data/job/version").asLong();
        assertThat(tagCodes(customerId)).contains("LEGACY_UNKNOWN").doesNotContain("V34_REMOVE");

        String rollback = action(admin, jobId, "rollback", version, "v34-remove-rollback")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.job.operation").value(ADD))
                .andExpect(jsonPath("$.data.job.rollbackOfJobId").value(jobId))
                .andExpect(jsonPath("$.data.job.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long rollbackId = objectMapper.readTree(rollback).at("/data/job/id").asLong();
        action(admin, rollbackId, "execute", 0, "v34-rollback-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("SUCCEEDED"));
        assertThat(tagCodes(customerId)).contains("LEGACY_UNKNOWN", "V34_REMOVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operation FROM crm_batch_tag_job WHERE id=?", String.class, jobId)).isEqualTo(REMOVE);
    }

    @Test
    void inactiveDictionaryFailsOnlyNewWritesAndRetryDoesNotRepeatTerminalItems() throws Exception {
        AuthCookies admin = login("admin");
        String city = city("retry");
        long first = customer(admin, "V34-retry-1", city);
        long second = customer(admin, "V34-retry-2", city);
        String previewBody = preview(admin, filterRequest(ADD, List.of("V34_VIP"), city), "v34-retry-preview")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String previewId = objectMapper.readTree(previewBody).at("/data/previewId").asText();
        long jobId = objectMapper.readTree(createJob(admin, previewId, "v34-retry-create")
                .andReturn().getResponse().getContentAsString()).at("/data/job/id").asLong();

        tag("V34_VIP", "V34贵宾", "INACTIVE");
        action(admin, jobId, "execute", 0, "v34-retry-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("FAILED"))
                .andExpect(jsonPath("$.data.job.failedCount").value(2))
                .andExpect(jsonPath("$.data.items.items[0].failureCode").value("TAG_DICTIONARY_INACTIVE"));
        long failedVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM crm_batch_tag_job WHERE id=?", Long.class, jobId);

        tag("V34_VIP", "V34贵宾", "ACTIVE");
        jdbcTemplate.update("UPDATE crm_batch_tag_job_item SET status='SUCCEEDED',attempt_count=1,"
                + "changed_tag_codes_json=JSON_ARRAY('V34_VIP') WHERE tenant_id=1 AND job_id=? AND customer_id=?", jobId, first);
        action(admin, jobId, "retry", failedVersion, "v34-retry-failed")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("SUCCEEDED"));
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM crm_batch_tag_job_item "
                + "WHERE job_id=? AND customer_id=?", Long.class, jobId, first)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_batch_tag_job_item "
                + "WHERE job_id=? AND customer_id=?", String.class, jobId, second)).isEqualTo("SUCCEEDED");

        preview(admin, filterRequest(ADD, List.of("UNKNOWN_NEW_TAG"), city), "v34-unknown-preview")
                .andExpect(status().isConflict());
        jdbcTemplate.update("INSERT INTO crm_customer_tag (tenant_id,customer_id,tag_code,created_by) "
                + "VALUES (1,?,'LEGACY_UNKNOWN',1)", first);
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/{id}/tags", first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagCodes", List.of("V34_VIP", "LEGACY_UNKNOWN")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tagCodes", org.hamcrest.Matchers.hasItem("LEGACY_UNKNOWN")));
        mockMvc.perform(admin.withAuth(put("/api/v1/customers/{id}/tags", first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tagCodes", List.of("V34_VIP", "UNKNOWN_NEW_TAG")))))
                .andExpect(status().isConflict());
    }

    @Test
    void workerRecoversLeaseAndSkipsOwnershipAndMergeConflicts() throws Exception {
        AuthCookies manager = login("manager1");
        String city = city("scope");
        long transferred = customer(manager, "V34-transferred", city);
        long merged = customer(manager, "V34-merged", city);
        long primary = customer(manager, "V34-primary", city);
        String previewBody = preview(manager, filterRequest(ADD, List.of("V34_VIP"), city), "v34-scope-preview")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String previewId = objectMapper.readTree(previewBody).at("/data/previewId").asText();
        long jobId = objectMapper.readTree(createJob(manager, previewId, "v34-scope-create")
                .andReturn().getResponse().getContentAsString()).at("/data/job/id").asLong();

        jdbcTemplate.update("UPDATE crm_customer SET owner_staff_id=3,team_id=2 WHERE id=?", transferred);
        jdbcTemplate.update("INSERT INTO crm_merge_case (tenant_id,primary_customer_id,secondary_customer_id,status,"
                + "reviewer_id,reviewed_at) VALUES (1,?,?,'CONFIRMED',1,CURRENT_TIMESTAMP(3))", primary, merged);
        jdbcTemplate.update("UPDATE crm_customer SET deleted=1 WHERE id=?", merged);
        action(manager, jobId, "execute", 0, "v34-scope-execute")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.status").value("PARTIAL_FAILED"))
                .andExpect(jsonPath("$.data.job.skippedCount").value(2))
                .andExpect(jsonPath("$.data.job.succeededCount").value(1));
        assertThat(jdbcTemplate.queryForObject("SELECT failure_code FROM crm_batch_tag_job_item "
                + "WHERE job_id=? AND customer_id=?", String.class, jobId, transferred)).isEqualTo("OWNERSHIP_OUT_OF_SCOPE");
        assertThat(jdbcTemplate.queryForObject("SELECT failure_code FROM crm_batch_tag_job_item "
                + "WHERE job_id=? AND customer_id=?", String.class, jobId, merged)).isEqualTo("MERGE_CONFLICT");
        assertThat(tagCodes(transferred)).doesNotContain("V34_VIP");
        assertThat(tagCodes(primary)).contains("V34_VIP");

        String secondPreviewBody = preview(manager, filterRequest(ADD, List.of("V34_VIP"), city),
                "v34-lease-preview").andReturn().getResponse().getContentAsString();
        String secondPreviewId = objectMapper.readTree(secondPreviewBody).at("/data/previewId").asText();
        long recoveryJob = objectMapper.readTree(createJob(manager, secondPreviewId, "v34-lease-create")
                .andReturn().getResponse().getContentAsString()).at("/data/job/id").asLong();
        jdbcTemplate.update("UPDATE crm_batch_tag_job SET status='RUNNING',lease_owner='crashed-worker',"
                + "lease_expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 MINUTE),version=version+1 WHERE id=?", recoveryJob);
        Object scheduler = applicationContext.getBean("batchTagJobScheduler");
        assertThat(scheduler.getClass().getMethod("runOnce").invoke(scheduler)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_batch_tag_job WHERE id=?",
                String.class, recoveryJob)).isEqualTo("SUCCEEDED");
    }

    @Test
    void institutionAccountIsRejected() throws Exception {
        AuthCookies institution = login("inst1");
        mockMvc.perform(institution.withAuth(post("/api/v1/batch-tag-jobs/preview"))
                        .header("Idempotency-Key", "v34-institution")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(filterRequest(ADD, List.of("V34_VIP"), "BJ"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/batch-tag-jobs").cookie(institution.cookies()))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions preview(
            AuthCookies user, Map<String, Object> request, String key) throws Exception {
        return mockMvc.perform(user.withAuth(post("/api/v1/batch-tag-jobs/preview"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(request)));
    }

    private org.springframework.test.web.servlet.ResultActions createJob(
            AuthCookies user, String previewId, String key) throws Exception {
        return mockMvc.perform(user.withAuth(post("/api/v1/batch-tag-jobs"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("previewId", previewId))));
    }

    private org.springframework.test.web.servlet.ResultActions action(
            AuthCookies user, long jobId, String action, long version, String key) throws Exception {
        return mockMvc.perform(user.withAuth(post("/api/v1/batch-tag-jobs/{id}/{action}", jobId, action))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", version))));
    }

    private Map<String, Object> filterRequest(String operation, List<String> tags, String city) {
        return Map.of("operation", operation, "tagCodes", tags,
                "source", Map.of("type", "FILTER", "filters", Map.of("cityCodes", List.of(city))),
                "page", 1, "size", 20);
    }

    private long customer(AuthCookies user, String nickname, String city) throws Exception {
        String response = mockMvc.perform(user.withAuth(post("/api/v1/customers"))
                        .header("Idempotency-Key", key("customer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("nickname", nickname, "ownerStaffId", 2, "cityCode", city))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/id").asLong();
    }

    private String city(String prefix) {
        String code = "V34_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        code = code.length() <= 60 ? code : code.substring(0, 60);
        jdbcTemplate.update("INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,status) "
                + "SELECT 1,id,?,'V34城市','ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities' "
                + "ON DUPLICATE KEY UPDATE status='ACTIVE'", code);
        return code;
    }

    private void tag(String code, String label, String status) {
        jdbcTemplate.update("INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,status) "
                + "SELECT 1,id,?,?,? FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='customerTags' "
                + "ON DUPLICATE KEY UPDATE option_label=VALUES(option_label),status=VALUES(status)", code, label, status);
    }

    private void tagOnCustomer(AuthCookies user, long customerId, String... codes) throws Exception {
        mockMvc.perform(user.withAuth(put("/api/v1/customers/{id}/tags", customerId))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("tagCodes", codes))))
                .andExpect(status().isOk());
    }

    private List<String> tagCodes(long customerId) {
        return jdbcTemplate.queryForList("SELECT tag_code FROM crm_customer_tag WHERE customer_id=? ORDER BY tag_code",
                String.class, customerId);
    }

    private long auditCount(long jobId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_audit_log "
                + "WHERE target_type='BATCH_TAG_JOB' AND target_id=?", Long.class, Long.toString(jobId));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String key(String prefix) {
        return "v34-" + prefix + "-" + UUID.randomUUID();
    }
}
