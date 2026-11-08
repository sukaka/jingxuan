package com.whale.crm.exporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.exporting.application.ExportWorker;
import com.whale.crm.exporting.domain.ExportType;
import com.whale.crm.exporting.infrastructure.ExportProperties;
import com.whale.crm.file.application.ClamAvScanner;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.files.enabled=true",
        "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only",
        "app.files.secret-key=synthetic-test-only",
        "app.exports.worker.enabled=false",
        "app.exports.worker.batch-size=20",
        "app.outbox.relay.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportApiIT extends AbstractMysqlIT {
    @MockitoBean PrivateObjectStorage storage;
    @MockitoBean ClamAvScanner scanner;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MobileSupport mobileSupport;
    @Autowired ExportWorker worker;
    @Autowired ExportProperties exportProperties;

    private final Map<String, byte[]> objects = new HashMap<>();
    private final List<byte[]> scannedContents = new ArrayList<>();
    private AuthCookies admin;
    private AuthCookies lead1;
    private AuthCookies manager1;

    @BeforeAll
    void loginUsers() throws Exception {
        admin = login("admin");
        lead1 = login("lead1");
        manager1 = login("manager1");
    }

    @BeforeEach
    void configureFileBoundaries() {
        objects.clear();
        scannedContents.clear();
        doAnswer(invocation -> {
            objects.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        doAnswer(invocation -> {
            objects.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());
        when(storage.stat(anyString())).thenAnswer(invocation -> {
            byte[] content = requiredObject(invocation.getArgument(0));
            return new PrivateObjectStorage.ObjectStat(md5(content), content.length, "text/csv;charset=UTF-8");
        });
        when(storage.getObject(anyString())).thenAnswer(invocation ->
                new ByteArrayInputStream(requiredObject(invocation.getArgument(0))));
        when(storage.presignGet(anyString(), anyInt())).thenAnswer(invocation ->
                "https://storage.invalid/export?key=" + invocation.getArgument(0)
                        + "&expires=" + invocation.getArgument(1) + "&signature=synthetic");
        when(scanner.scan(any())).thenAnswer(invocation -> {
            byte[] content = Files.readAllBytes(invocation.getArgument(0));
            scannedContents.add(content);
            return ClamAvScanner.ScanResult.CLEAN;
        });
    }

    @Test
    void x01ManagerCannotExportAllOrDownloadCachedFileAndDenialsContainReason() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000101", unique("X01-CITY"), "ordinary");
        JsonNode cached = createSucceeded(lead1, ExportType.CUSTOMER,
                List.of("customerNo", "mobile"), unique("X01 缓存文件 "), Map.of("customerId", customer.id()), null);
        String reason = unique("X01 普通管家尝试全量导出 ");

        mockMvc.perform(manager1.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", unique("x01-request-"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("exportType", "CUSTOMER", "columns", List.of("customerNo", "mobile"),
                                "reason", reason, "scope", Map.of("teamIds", List.of(1, 2)))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        Map<String, Object> denial = jdbcTemplate.queryForMap(
                "SELECT result,reason,denial_code FROM crm_export_access_log "
                        + "WHERE tenant_id=1 AND actor_user_id=2 AND action='REQUEST' AND reason=? ORDER BY id DESC LIMIT 1", reason);
        assertThat(denial.get("result")).isEqualTo("DENIED");
        assertThat(denial.get("denial_code")).isEqualTo("EXPORT_CREATE_FORBIDDEN");
        assertThat(denial.get("reason")).isEqualTo(reason);

        clearInvocations(storage);
        mockMvc.perform(get("/api/v1/exports/{id}/download", cached.get("id").asLong()).cookie(manager1.cookies()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
        verify(storage, never()).presignGet(anyString(), anyInt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_export_access_log WHERE tenant_id=1 AND actor_user_id=2 "
                        + "AND action='DOWNLOAD' AND result='DENIED' AND denial_code='EXPORT_READ_FORBIDDEN'", Long.class))
                .isPositive();

        mockMvc.perform(get("/api/v1/files/{id}/download", cached.get("fileId").asLong()).cookie(lead1.cookies()))
                .andExpect(status().isForbidden());
        verify(storage, never()).presignGet(anyString(), anyInt());
    }

    @Test
    void x02TeamExportContainsOnlyRealAuthorizedCsvRowsAndMaskedMobile() throws Exception {
        String city = unique("X02-CITY-");
        CustomerFixture teamOne = insertCustomer(1L, 2L, "13900000201", city, "=1+1");
        CustomerFixture teamTwo = insertCustomer(2L, 3L, "13900000202", city, "OTHER-TEAM-SECRET");
        String reason = unique("X02 本组客户复核 ");

        JsonNode job = createSucceeded(lead1, ExportType.CUSTOMER,
                List.of("customerNo", "displayName", "mobile", "teamId", "remark"), reason,
                Map.of("cityCode", city), Map.of("teamIds", List.of(1)));

        assertThat(job.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.get("rowCount").asLong()).isEqualTo(1L);
        Map<String, Object> metadata = fileMetadata(job.get("fileId").asLong());
        assertThat(metadata.get("classification")).isEqualTo("EXPORT");
        assertThat(metadata.get("scan_status")).isEqualTo("CLEAN");
        assertThat(metadata.get("institution_id")).isNull();
        assertThat(metadata.get("sha256").toString()).hasSize(64);
        assertThat(((java.time.LocalDateTime) metadata.get("expires_at")).toInstant(java.time.ZoneOffset.UTC))
                .isAfter(Instant.now());

        String csv = csv(job);
        assertThat(csv).contains(teamOne.customerNo(), "139****0201", "'=1+1");
        assertThat(csv).doesNotContain("13900000201", "13900000202", teamTwo.customerNo(),
                "OTHER-TEAM-SECRET", teamOne.cipher(), teamOne.hash(), teamTwo.cipher(), teamTwo.hash(),
                "mobileCipher", "mobileHash");
        byte[] object = requiredObject(metadata.get("object_key").toString());
        assertThat(scannedContents).anyMatch(scanned -> Arrays.equals(scanned, object));

        Map<String, Object> requestAudit = jdbcTemplate.queryForMap(
                "SELECT result,reason FROM crm_export_access_log WHERE tenant_id=1 AND job_id=? "
                        + "AND action='REQUEST' AND result='ALLOWED' ORDER BY id DESC LIMIT 1", job.get("id").asLong());
        assertThat(requestAudit.get("reason")).isEqualTo(reason);

        mockMvc.perform(get("/api/v1/exports/{id}/download", job.get("id").asLong()).cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileId").value(job.get("fileId").asLong()))
                .andExpect(jsonPath("$.data.downloadUrl").value(org.hamcrest.Matchers.containsString("signature=synthetic")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_export_access_log WHERE tenant_id=1 AND job_id=? "
                        + "AND action='DOWNLOAD' AND result='ALLOWED' AND reason=?", Long.class,
                job.get("id").asLong(), reason)).isEqualTo(1L);

        String wideningReason = unique("X02 试图扩大团队范围 ");
        mockMvc.perform(lead1.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", unique("x02-widening-"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("exportType", "CUSTOMER", "columns", List.of("customerNo"),
                                "reason", wideningReason, "filters", Map.of("cityCode", city),
                                "scope", Map.of("teamIds", List.of(1, 2)))))))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_export_access_log WHERE tenant_id=1 AND actor_user_id=4 "
                        + "AND action='REQUEST' AND result='DENIED' AND reason=?", Long.class, wideningReason))
                .isEqualTo(1L);

        mockMvc.perform(get("/api/v1/exports?page=1&size=1").cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(get("/api/v1/exports/access-logs?jobId={id}&page=1&size=100", job.get("id").asLong())
                        .cookie(lead1.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void allFourDatasetsGenerateWhitelistedIntegerFenCsv() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000301", unique("ALL-CITY-"), "ordinary");
        String orderNo = unique("IT-ORDER-");
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                        + "source_system,order_type_code,status,receiver_type,list_amount_fen,discount_amount_fen,"
                        + "payable_fen,paid_fen,refunded_fen,ordered_at) VALUES (1,?,?,2,2,'CRM','REGULAR','PAID',"
                        + "'PLATFORM',12345,0,12345,12345,0,UTC_TIMESTAMP(3))", orderNo, customer.id());
        jdbcTemplate.update("INSERT INTO crm_followup (tenant_id,customer_id,owner_staff_id,channel_code,contact_at,"
                + "summary,estimated_amount_fen,created_by) VALUES (1,?,2,'WECOM',UTC_TIMESTAMP(3),'@SUM(1)',6789,2)", customer.id());
        String appointmentNo = unique("IT-APPT-");
        jdbcTemplate.update("INSERT INTO crm_appointment (tenant_id,appointment_no,customer_id,institution_id,"
                + "owner_staff_id,team_id,scheduled_at,status,created_by) VALUES (1,?,?,1,2,1,UTC_TIMESTAMP(3),'SERVICE_DONE',2)",
                appointmentNo, customer.id());
        long appointmentId = jdbcTemplate.queryForObject(
                "SELECT id FROM crm_appointment WHERE tenant_id=1 AND appointment_no=?", Long.class, appointmentNo);
        jdbcTemplate.update("INSERT INTO crm_redemption (tenant_id,appointment_id,customer_id,institution_id,owner_staff_id,"
                        + "team_id,service_at,evidence_ref,status,settlement_status,idempotency_key,posted_by,posted_at) "
                        + "VALUES (1,?,?,1,2,1,UTC_TIMESTAMP(3),'SECRET-EVIDENCE','POSTED','PENDING_VERIFICATION',?,10,UTC_TIMESTAMP(3))",
                appointmentId, customer.id(), unique("it-redemption-"));

        JsonNode customerJob = createSucceeded(admin, ExportType.CUSTOMER, List.of("customerNo", "mobile"),
                unique("四类客户导出 "), Map.of("customerId", customer.id()), null);
        JsonNode orderJob = createSucceeded(admin, ExportType.ORDER, List.of("orderNo", "payableFen", "paidFen"),
                unique("四类订单导出 "), Map.of("customerId", customer.id()), null);
        JsonNode followupJob = createSucceeded(admin, ExportType.FOLLOWUP,
                List.of("followupId", "estimatedAmountFen", "summary"), unique("四类跟进导出 "),
                Map.of("customerId", customer.id()), null);
        JsonNode redemptionJob = createSucceeded(admin, ExportType.REDEMPTION,
                List.of("redemptionId", "customerNo", "institutionId", "status"), unique("四类核销导出 "),
                Map.of("customerId", customer.id()), null);

        assertThat(csv(customerJob)).contains(customer.customerNo(), "139****0301").doesNotContain("13900000301");
        assertThat(csv(orderJob)).contains(orderNo, "12345").doesNotContain("123.45");
        assertThat(csv(followupJob)).contains("6789,'@SUM(1)");
        assertThat(csv(redemptionJob)).contains(customer.customerNo(), "POSTED")
                .doesNotContain("SECRET-EVIDENCE", "idempotencyKey");
    }

    @Test
    void downloadRejectsExpiryScopeChangeAndRevokedPermission() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000401", unique("DL-CITY-"), "ordinary");
        JsonNode expired = createSucceeded(lead1, ExportType.CUSTOMER, List.of("customerNo"),
                unique("过期下载测试 "), Map.of("customerId", customer.id()), null);
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(120));
        jdbcTemplate.update("UPDATE crm_export_job SET expires_at=? WHERE id=?", past, expired.get("id").asLong());
        jdbcTemplate.update("UPDATE crm_file_object SET expires_at=? WHERE id=?", past, expired.get("fileId").asLong());
        clearInvocations(storage);

        mockMvc.perform(get("/api/v1/exports/{id}/download", expired.get("id").asLong()).cookie(lead1.cookies()))
                .andExpect(status().isNotFound());
        verify(storage, never()).presignGet(anyString(), anyInt());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_export_access_log WHERE job_id=? "
                + "AND action='DOWNLOAD' AND result='DENIED' AND denial_code='EXPORT_EXPIRED'", Long.class,
                expired.get("id").asLong())).isEqualTo(1L);

        JsonNode scoped = createSucceeded(lead1, ExportType.CUSTOMER, List.of("customerNo"),
                unique("范围回收测试 "), Map.of("customerId", customer.id()), null);
        jdbcTemplate.update("DELETE FROM sys_data_scope WHERE tenant_id=1 AND user_id=4 AND resource='customer'");
        try {
            mockMvc.perform(get("/api/v1/exports/{id}/download", scoped.get("id").asLong()).cookie(lead1.cookies()))
                    .andExpect(status().isForbidden());
        } finally {
            jdbcTemplate.update("INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id) "
                    + "VALUES (1,4,'customer','TEAM',1)");
        }

        JsonNode revoked = createSucceeded(lead1, ExportType.CUSTOMER, List.of("customerNo"),
                unique("权限回收测试 "), Map.of("customerId", customer.id()), null);
        jdbcTemplate.update("DELETE rp FROM sys_role_permission rp JOIN sys_role r ON r.id=rp.role_id "
                + "JOIN sys_permission p ON p.id=rp.permission_id WHERE r.tenant_id=1 AND r.role_code='MANAGER_LEAD' "
                + "AND p.permission_code='export:read'");
        try {
            mockMvc.perform(get("/api/v1/exports/{id}/download", revoked.get("id").asLong()).cookie(lead1.cookies()))
                    .andExpect(status().isForbidden());
        } finally {
            jdbcTemplate.update("INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id) "
                    + "SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id "
                    + "WHERE r.tenant_id=1 AND r.role_code='MANAGER_LEAD' AND p.permission_code='export:read'");
        }
    }

    @Test
    void rowLimitAndExpiredLeaseRecoveryPersistRealState() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000501", unique("JOB-CITY-"), "ordinary");
        insertCustomer(2L, 3L, "13900000502", unique("JOB-CITY-"), "ordinary");
        int originalMaxRows = exportProperties.getMaxRows();
        exportProperties.setMaxRows(1);
        try {
            long jobId = createJob(admin, ExportType.CUSTOMER, List.of("customerNo"), unique("行数限制测试 "), null, null);
            worker.runOnce();
            mockMvc.perform(get("/api/v1/exports/{id}", jobId).cookie(admin.cookies()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("FAILED"))
                    .andExpect(jsonPath("$.data.lastError").value("ROW_LIMIT_EXCEEDED"))
                    .andExpect(jsonPath("$.data.fileId").doesNotExist());
        } finally {
            exportProperties.setMaxRows(originalMaxRows);
        }

        long recoveredId = createJob(admin, ExportType.CUSTOMER, List.of("customerNo"), unique("崩溃租约恢复 "),
                Map.of("customerId", customer.id()), null);
        jdbcTemplate.update("UPDATE crm_export_job SET status='RUNNING',lease_owner='crashed-worker',"
                + "lease_expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?", recoveredId);
        worker.runOnce();
        mockMvc.perform(get("/api/v1/exports/{id}", recoveredId).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.rowCount").value(1));

        clearInvocations(storage);
        assertThat(worker.runOnce()).isZero();
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
    }

    @Test
    void idempotencyReturnsSamePersistedJobAndDifferentPayloadConflicts() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000601", unique("IDEM-CITY-"), "ordinary");
        String key = unique("it-idempotent-");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportType", "CUSTOMER");
        body.put("columns", List.of("customerNo"));
        body.put("reason", unique("幂等导出测试 "));
        body.put("filters", Map.of("customerId", customer.id()));

        MvcResult first = mockMvc.perform(admin.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body))))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(first.getResponse().getContentAsString()).get("data").get("id").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_export_job WHERE tenant_id=1 "
                + "AND requested_by=1 AND idempotency_key=?", Long.class, key)).isEqualTo(1L);

        body.put("reason", unique("不同请求冲突 "));
        mockMvc.perform(admin.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json(body))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_export_access_log WHERE job_id=? "
                + "AND action='REQUEST' AND result='DENIED' AND denial_code='IDEMPOTENCY_CONFLICT'", Long.class, id))
                .isEqualTo(1L);
        worker.runOnce();
    }

    @Test
    void nonCleanScanFailsJobWithoutObjectOrDownload() throws Exception {
        CustomerFixture customer = insertCustomer(1L, 2L, "13900000701", unique("SCAN-CITY-"), "ordinary");
        doAnswer(invocation -> ClamAvScanner.ScanResult.FOUND).when(scanner).scan(any());
        long id = createJob(admin, ExportType.CUSTOMER, List.of("customerNo"), unique("扫描阻断测试 "),
                Map.of("customerId", customer.id()), null);

        worker.runOnce();

        mockMvc.perform(get("/api/v1/exports/{id}", id).cookie(admin.cookies()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.lastError").value("EXPORT_FILE_REJECTED"))
                .andExpect(jsonPath("$.data.fileId").doesNotExist());
        assertThat(objects).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_export_access_log WHERE job_id=? "
                + "AND action='REQUEST' AND result='DENIED' AND denial_code='EXPORT_FILE_REJECTED'", Long.class, id))
                .isEqualTo(1L);
        mockMvc.perform(get("/api/v1/exports/{id}/download", id).cookie(admin.cookies()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.downloadUrl").doesNotExist());
    }

    private JsonNode createSucceeded(AuthCookies auth, ExportType type, List<String> columns, String reason,
                                     Map<String, Object> filters, Map<String, Object> scope) throws Exception {
        long id = createJob(auth, type, columns, reason, filters, scope);
        worker.runOnce();
        MvcResult result = mockMvc.perform(get("/api/v1/exports/{id}", id).cookie(auth.cookies()))
                .andExpect(status().isOk()).andReturn();
        JsonNode job = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(job.get("status").asText()).as(job.toPrettyString()).isEqualTo("SUCCEEDED");
        return job;
    }

    private long createJob(AuthCookies auth, ExportType type, List<String> columns, String reason,
                           Map<String, Object> filters, Map<String, Object> scope) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exportType", type.name());
        body.put("columns", columns);
        body.put("reason", reason);
        if (filters != null) body.put("filters", filters);
        if (scope != null) body.put("scope", scope);
        MvcResult result = mockMvc.perform(auth.withAuth(post("/api/v1/exports")
                        .header("Idempotency-Key", unique("it-export-"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(body))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private CustomerFixture insertCustomer(long teamId, long ownerId, String mobile, String city, String remark) {
        String customerNo = unique("IT-C-");
        String cipher = mobileSupport.encrypt(mobile);
        String hash = mobileSupport.hash(mobile);
        jdbcTemplate.update("INSERT INTO crm_customer (tenant_id,owner_staff_id,team_id,customer_no,display_name,remark,"
                        + "mobile_cipher,mobile_hash,city_code,customer_status) VALUES (1,?,?,?,?,?,?,?,?,'ACTIVE')",
                ownerId, teamId, customerNo, "synthetic-" + customerNo, remark, cipher, hash, city);
        long id = jdbcTemplate.queryForObject("SELECT id FROM crm_customer WHERE tenant_id=1 AND customer_no=?",
                Long.class, customerNo);
        return new CustomerFixture(id, customerNo, cipher, hash);
    }

    private Map<String, Object> fileMetadata(long fileId) {
        return jdbcTemplate.queryForMap("SELECT object_key,classification,institution_id,scan_status,sha256,expires_at "
                + "FROM crm_file_object WHERE tenant_id=1 AND id=?", fileId);
    }

    private String csv(JsonNode job) {
        String key = jdbcTemplate.queryForObject("SELECT object_key FROM crm_file_object WHERE tenant_id=1 AND id=?",
                String.class, job.get("fileId").asLong());
        return new String(requiredObject(key), StandardCharsets.UTF_8);
    }

    private byte[] requiredObject(String key) {
        byte[] content = objects.get(key);
        if (content == null) {
            throw new AssertionError("Missing synthetic private object " + key);
        }
        return content;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String md5(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record CustomerFixture(long id, String customerNo, String cipher, String hash) {
    }
}
