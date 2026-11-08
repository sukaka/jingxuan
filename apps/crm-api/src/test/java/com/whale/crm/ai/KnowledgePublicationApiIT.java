package com.whale.crm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.ai.application.KnowledgeSyncExecutionService;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.files.enabled=true", "app.files.endpoint=http://127.0.0.1:9",
        "app.files.access-key=synthetic-test-only", "app.files.secret-key=synthetic-test-only",
        "app.outbox.relay.enabled=false"
})
class KnowledgePublicationApiIT extends AbstractMysqlIT {
    private static final AtomicLong IDS = new AtomicLong(920000L);
    private static final String TEXT = "# 合成服务流程\n仅用于知识发布测试";
    @MockitoBean PrivateObjectStorage storage;
    @MockitoBean com.whale.crm.file.application.ClamAvScanner scanner;
    @MockitoBean KnowledgeWorkerClient workerClient;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired KnowledgeRepository knowledgeRepository;
    @Autowired KnowledgeSyncExecutionService executionService;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private long fileId;
    private long materialId;
    private long revisionId;
    private String objectKey;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        objects.clear();
        when(scanner.scan(any())).thenReturn(com.whale.crm.file.application.ClamAvScanner.ScanResult.CLEAN);
        when(storage.getObject(anyString())).thenAnswer(invocation -> {
            byte[] content = objects.get(invocation.getArgument(0));
            if (content == null) throw new IllegalStateException("测试对象不存在");
            return new ByteArrayInputStream(content);
        });
        doAnswer(invocation -> {
            InputStream content = invocation.getArgument(1);
            objects.put(invocation.getArgument(0), content.readAllBytes());
            return null;
        }).when(storage).putObject(anyString(), any(InputStream.class), anyLong(), anyString());
        doAnswer(invocation -> {
            objects.remove(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());
        jdbcTemplate.update("DELETE FROM crm_outbox_event WHERE tenant_id=1 AND aggregate_type='knowledge_document'");
        jdbcTemplate.update("DELETE FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group='crm-knowledge-sync'");
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id=1 AND scope_code='ai.knowledge.publish'");
        jdbcTemplate.update("DELETE FROM crm_knowledge_sync_job WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_knowledge_document WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_dify_knowledge_config WHERE tenant_id=1");
        jdbcTemplate.update("INSERT INTO crm_dify_knowledge_config (tenant_id,provider,dataset_id,sync_enabled,"
                + "timeout_seconds,enabled,connection_status) VALUES (1,'mock','mock-dataset',TRUE,30,FALSE,'UNVERIFIED')");
        createSource("APPROVED");
    }

    @Test
    void approvedCleanMarkdownPublishesWaitingJobWithIdempotencyAndMinimalOutbox() throws Exception {
        AuthCookies admin = login("admin");
        String key = key("publish");
        String response = publish(admin, key, 2L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.data.revisionId").value(revisionId))
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        String sealedKey = jdbcTemplate.queryForObject("SELECT object_key FROM crm_file_object WHERE id=?",
                String.class, fileId);
        assertThat(sealedKey).startsWith("sealed/").isNotEqualTo(objectKey);
        assertThat(objects.get(sealedKey)).isEqualTo(TEXT.getBytes(StandardCharsets.UTF_8));
        assertThat(response).doesNotContain(TEXT);

        publish(admin, key, 2L).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId));
        publish(admin, key("duplicate-content"), 2L)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(jobId));
        publish(admin, key, 1L).andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_knowledge_sync_job WHERE tenant_id=1", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND aggregate_type='knowledge_document' AND aggregate_id=?", Long.class, Long.toString(jobId))).isEqualTo(1);
        String rawPayload = jdbcTemplate.queryForObject("SELECT payload_json FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND aggregate_type='knowledge_document' AND aggregate_id=?", String.class, Long.toString(jobId));
        JsonNode payload = objectMapper.readTree(rawPayload);
        assertThat(payload.at("/jobId").asLong()).isEqualTo(jobId);
        assertThat(payload.at("/documentId").asLong()).isEqualTo(documentId);
        assertThat(rawPayload).doesNotContain(TEXT);
        String documentStatus = jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_document WHERE id=?",
                String.class, documentId);
        assertThat(documentStatus).isEqualTo("WAITING");
    }

    @Test
    void managementListRetryAndDisableEnforcePermissionsAndIdempotency() throws Exception {
        AuthCookies admin = login("admin");
        String response = publish(admin, key("manage"), 2L).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/ai/knowledge/documents").cookie(admin.cookies()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        AuthCookies manager = login("manager1");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/ai/knowledge/documents").cookie(manager.cookies()))
                .andExpect(status().isForbidden());

        jdbcTemplate.update("UPDATE crm_knowledge_sync_job SET status='FAILED',attempt_count=3,version=3 WHERE id=?", jobId);
        String retryKey = key("retry");
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/sync-jobs/{id}/retry", jobId))
                        .header("Idempotency-Key", retryKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attemptCount").value(0));
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/sync-jobs/{id}/retry", jobId))
                        .header("Idempotency-Key", retryKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attemptCount").value(0));

        jdbcTemplate.update("UPDATE crm_knowledge_document SET dify_document_id='doc-disable',"
                + "active_revision_id=?,status='ACTIVE' WHERE id=?", revisionId, documentId);
        String disableKey = key("disable");
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/documents/{id}/disable", documentId))
                        .header("Idempotency-Key", disableKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/documents/{id}/disable", documentId))
                        .header("Idempotency-Key", disableKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"));
        org.mockito.Mockito.verify(workerClient, org.mockito.Mockito.times(1)).disableDocument(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("doc-disable"),
                org.mockito.ArgumentMatchers.eq("knowledge-disable-" + documentId));
    }

    @Test
    void consumerMovesWaitingToPendingAndDeduplicatesInbox() throws Exception {
        AuthCookies admin = login("admin");
        String response = publish(admin, key("consume"), 2L).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        com.whale.crm.ai.application.KnowledgeSyncConsumer consumer =
                new com.whale.crm.ai.application.KnowledgeSyncConsumer(knowledgeRepository, jdbcTemplate,
                        objectMapper, transactionManager);
        String message = """
                {"event_id":"it-event-%d","event_type":"knowledge.sync.requested.v1","event_version":1,
                "tenant_id":1,"aggregate_type":"knowledge_document","aggregate_id":"%d",
                "occurred_at":"2026-10-28T00:00:00Z","correlation_id":"it-event-%d",
                "payload":{"jobId":%d,"documentId":%d}}
                """.formatted(jobId, jobId, jobId, jobId, documentId);

        consumer.consume(message);
        consumer.consume(message);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_sync_job WHERE id=?", String.class, jobId))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-knowledge-sync' AND event_id=?", Long.class, "it-event-" + jobId)).isEqualTo(1);
    }

    @Test
    void mockExecutionTransitionsJobAndActivatesDocument() throws Exception {
        AuthCookies admin = login("admin");
        String response = publish(admin, key("execute"), 2L).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        assertThat(knowledgeRepository.markPending(1L, jobId, 0L)).isEqualTo(1);
        java.time.Instant now = java.time.Instant.now();
        assertThat(knowledgeRepository.claimNew(1L, jobId, 1L, "it-owner", now, now.plusSeconds(30))).isEqualTo(1);
        when(workerClient.syncDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new KnowledgeWorkerClient.DocumentSyncResult("req", "mock", "doc-it-1", "batch-it-1", "AVAILABLE", null));

        executionService.execute(1L, jobId, "it-owner");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_sync_job WHERE id=?", String.class, jobId))
                .isEqualTo("SUCCEEDED");
        Map<String, Object> document = jdbcTemplate.queryForMap("SELECT status,active_revision_id,dify_document_id "
                + "FROM crm_knowledge_document WHERE id=?", documentId);
        assertThat(document.get("status")).isEqualTo("ACTIVE");
        assertThat(((Number) document.get("active_revision_id")).longValue()).isEqualTo(revisionId);
        assertThat(document.get("dify_document_id")).isEqualTo("doc-it-1");
    }

    @Test
    void disablingDuringSynchronizationCannotBeOverriddenByLateSuccess() throws Exception {
        AuthCookies admin = login("admin");
        String response = publish(admin, key("disable-race"), 2L).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        assertThat(knowledgeRepository.markPending(1L, jobId, 0L)).isEqualTo(1);
        java.time.Instant now = java.time.Instant.now();
        assertThat(knowledgeRepository.claimNew(1L, jobId, 1L, "it-owner", now, now.plusSeconds(30))).isEqualTo(1);
        when(workerClient.syncDocument(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            assertThat(knowledgeRepository.disableDocument(1L, documentId, 0L)).isEqualTo(1);
            return new KnowledgeWorkerClient.DocumentSyncResult("req", "mock", "doc-race", "batch-race", "AVAILABLE", null);
        });

        executionService.execute(1L, jobId, "it-owner");

        Map<String, Object> document = jdbcTemplate.queryForMap(
                "SELECT status,active_revision_id,version FROM crm_knowledge_document WHERE id=?", documentId);
        assertThat(document.get("status")).isEqualTo("DISABLED");
        assertThat(document.get("active_revision_id")).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_sync_job WHERE id=?",
                String.class, jobId)).isNotEqualTo("SUCCEEDED");
    }

    @Test
    void disabledDocumentCannotBeClaimedRetriedOrRepublished() throws Exception {
        AuthCookies admin = login("admin");
        String response = publish(admin, key("disabled-terminal"), 2L).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        assertThat(knowledgeRepository.disableDocument(1L, documentId, 0L)).isEqualTo(1);
        java.time.Instant now = java.time.Instant.now();

        assertThat(knowledgeRepository.claimable(1L, now, 10)).isEmpty();
        assertThat(knowledgeRepository.claimNew(1L, jobId, 0L, "it-owner", now, now.plusSeconds(30))).isZero();
        publish(admin, key("disabled-republish"), 2L).andExpect(status().isConflict());

        jdbcTemplate.update("UPDATE crm_knowledge_sync_job SET status='FAILED',attempt_count=3,version=1 WHERE id=?", jobId);
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/sync-jobs/{id}/retry", jobId))
                        .header("Idempotency-Key", key("disabled-retry")))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_sync_job WHERE id=?",
                String.class, jobId)).isEqualTo("FAILED");
    }

    @Test
    void nonAdminCannotPublishAndUnapprovedSourceIsRejected() throws Exception {
        AuthCookies manager = login("manager1");
        publish(manager, key("forbidden"), 2L).andExpect(status().isForbidden());

        createSource("DRAFT");
        AuthCookies admin = login("admin");
        publish(admin, key("draft"), 2L).andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_knowledge_sync_job WHERE tenant_id=1", Long.class)).isZero();
    }

    private void createSource(String status) throws Exception {
        fileId = IDS.incrementAndGet();
        materialId = IDS.incrementAndGet();
        revisionId = IDS.incrementAndGet();
        objectKey = "it-kb-file-" + fileId;
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbcTemplate.update("INSERT INTO crm_file_object (id,tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                fileId, 1L, objectKey, "guide.md", "text/markdown", bytes.length, hash,
                "MATERIAL", 1L, "CLEAN", 1L);
        jdbcTemplate.update("INSERT INTO crm_material (id,tenant_id,material_no,title,category_code,institution_id,"
                        + "review_status,created_by) VALUES (?,?,?,?,?,?,?,?)",
                materialId, 1L, "IT-KB-MAT-" + materialId, "合成服务流程", "GUIDE", 1L, status, 1L);
        jdbcTemplate.update("INSERT INTO crm_material_revision (id,tenant_id,material_id,revision_no,title,category_code,"
                        + "institution_id,file_id,status,created_by,version) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                revisionId, 1L, materialId, 1, "合成服务流程", "GUIDE", 1L, fileId, status, 1L, 2L);
        if ("APPROVED".equals(status)) {
            jdbcTemplate.update("UPDATE crm_material SET current_revision_id=? WHERE id=?", revisionId, materialId);
        }
        objects.put(objectKey, bytes);
    }

    private org.springframework.test.web.servlet.ResultActions publish(AuthCookies actor, String key, long version) throws Exception {
        Map<String, Object> body = Map.of("materialId", materialId, "revisionId", revisionId, "version", version);
        return mockMvc.perform(actor.withAuth(post("/api/v1/ai/knowledge/documents/publications"))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String key(String prefix) {
        return "it-kb-publication-" + prefix + "-" + UUID.randomUUID();
    }
}
