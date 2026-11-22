package com.whale.crm.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.ai.application.AiChatDelegationService;
import com.whale.crm.ai.application.AiChatDelegationWorker;
import com.whale.crm.ai.application.AiJobWorker;
import com.whale.crm.ai.application.AiWorkerClient;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.ExternalContactMessageCommand;
import com.whale.crm.integration.wecom.adapter.WecomExternalContactSenderRouter;
import com.whale.crm.notification.domain.WecomSendResult;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F12 AI 工作流、SSRF 拦截、确认驳回防 NPE、发送栅栏重试恢复与 Java<->Python 实时契约端到端闭环：
 * - H-SEC-02：PUT /api/v1/ai/config 与 PUT /api/v1/ai/knowledge/config 经 HTTP 拦截 SSRF 地址 (400)
 * - H-BUG-05：POST /api/v1/ai/jobs -> execute -> GET -> POST /confirmation REJECT(taskId=null, 200, AI_JOB_REJECTED)
 *   & CONFIRM(生成任务) & decision=null(400)
 * - H-BUG-07：真实 MySQL AiChatDelegationWorker.runOnce() + AiChatSendFenceMapper.xml#releaseForRetry：
 *   RETRYABLE -> in_flight=0, finished_at=NULL -> 重试 SENT(finished_at!=NULL) -> 重复拦截 -> emergency-stop & HUMAN_TAKEOVER
 * - Live Java <-> Python ai-worker 契约：拉起临时 uvicorn mock 模式，真实 AiWorkerClient & KnowledgeWorkerClient 环回校验
 */
@SpringBootTest(properties = {
        "app.ai.worker.enabled=true",
        "app.ai.worker.delay-ms=600000",
        "app.ai.delegation.worker.enabled=true",
        "app.ai.delegation.worker.delay-ms=600000",
        "app.outbox.relay.enabled=false",
        "app.exports.worker.enabled=false"
})
class AiWorkflowAndFenceE2EIT extends AbstractE2EIT {

    @MockitoBean
    private AiWorkerClient aiWorkerClient;

    @MockitoBean
    private PrivateObjectStorage objectStorage;

    @MockitoBean(name = "wecomExternalContactSenderRouter")
    private WecomExternalContactSenderRouter externalContactSender;

    @Autowired
    private AesGcmCipher cipher;

    @Autowired
    private AiJobWorker aiJobWorker;

    @Autowired
    private AiChatDelegationService delegationService;

    @Autowired
    private AiChatDelegationWorker delegationWorker;

    private final Map<String, byte[]> wecomObjectStore = new ConcurrentHashMap<>();

    @BeforeEach
    void resetAiTables() {
        wecomObjectStore.clear();
        when(objectStorage.getObject(anyString())).thenAnswer(inv -> {
            byte[] bytes = wecomObjectStore.get(inv.getArgument(0));
            if (bytes == null) {
                throw new IllegalStateException("Missing mock wecom object: " + inv.getArgument(0));
            }
            return new ByteArrayInputStream(bytes);
        });
        jdbcTemplate.update("DELETE FROM crm_ai_insight WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_job WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_provider_config WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_dify_knowledge_config WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_chat_delegation_customer WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_chat_delegation_scope WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_ai_chat_delegation_policy WHERE tenant_id=1");
    }

    @Test
    void ssrfUrlsAreRejectedOverHttpOnAiAndKnowledgeConfigs() throws Exception {
        AuthCookies admin = login("admin");
        List<String> ssrfUrls = List.of(
                "https://127.0.0.1/v1",
                "https://169.254.169.254/v1",
                "https://10.0.0.1/v1",
                "https://[::1]/v1",
                "http://public.example.com/v1");

        for (String badUrl : ssrfUrls) {
            mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                            .header("Idempotency-Key", uniqueKey("e2e-ssrf-ai"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "provider", "openai_compatible",
                                    "baseUrl", badUrl,
                                    "apiKey", "sk-synthetic",
                                    "model", "gpt-test",
                                    "timeoutSeconds", 15,
                                    "enabled", true,
                                    "version", 0))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            mockMvc.perform(admin.withAuth(put("/api/v1/ai/knowledge/config"))
                            .header("Idempotency-Key", uniqueKey("e2e-ssrf-dify"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "provider", "dify",
                                    "baseUrl", badUrl,
                                    "apiKey", "dify-synthetic",
                                    "datasetId", "ds-1",
                                    "datasetApiKey", "ds-key-synthetic",
                                    "timeoutSeconds", 15,
                                    "enabled", true,
                                    "syncEnabled", true,
                                    "version", 0))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        assertThat(queryInt("SELECT COUNT(*) FROM crm_ai_provider_config WHERE tenant_id=1")).isZero();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_dify_knowledge_config WHERE tenant_id=1")).isZero();
    }

    @Test
    void aiJobHttpLifecycleRejectConfirmAndNullDecisionValidation() throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");

        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", uniqueKey("e2e-ai-cfg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("provider", "mock", "model", "mock-rules-v1",
                                "timeoutSeconds", 20, "enabled", true, "version", 0))))
                .andExpect(status().isOk());

        long customerId = data(manager1.withAuth(post("/api/v1/customers"))
                .header("Idempotency-Key", uniqueKey("e2e-aijob-cust"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", shortUuid("AiJobCust")))))
                .at("/id").asLong();

        long msg1 = insertReadableTextWecomMessage(customerId, 2L, "想咨询水光针预约");
        long msg2 = insertReadableTextWecomMessage(customerId, 2L, "明天下午可以安排吗");

        when(aiWorkerClient.extract(anyLong(), anyLong(), anyList(), anyList(), anyList(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<AiWorkerClient.MessageInput> inputs = inv.getArgument(2);
                    List<String> evidenceIds = inputs.stream().map(AiWorkerClient.MessageInput::id).toList();
                    return new AiWorkerClient.ExtractionResult("mock", "mock-rules-v1",
                            new AiWorkerClient.InsightData("APPOINTMENT_INTENT", List.of(), null, "明天下午",
                                    null, evidenceIds, new BigDecimal("0.88"), List.of("CREATE_FOLLOWUP_DRAFT"),
                                    "UNVERIFIED", false, "客户咨询水光针预约", "可以为您预约明天下午", null,
                                    List.of()));
                });

        // Job 1: Create -> Execute -> Get PROPOSED -> Confirm REJECT (no NPE, taskId=null, AI_JOB_REJECTED)
        long rejectJobId = data(manager1.withAuth(post("/api/v1/ai/jobs"))
                .header("Idempotency-Key", uniqueKey("e2e-job-rej"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("messageIds", List.of(msg1)))))
                .at("/id").asLong();

        assertThat(aiJobWorker.runOnce()).isGreaterThanOrEqualTo(1);

        JsonNode proposedJob1 = data(get("/api/v1/ai/jobs/" + rejectJobId).cookie(manager1.cookies()));
        assertThat(proposedJob1.at("/status").asText()).isEqualTo("PROPOSED");
        assertThat(proposedJob1.at("/insight/factStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(proposedJob1.at("/insight/allowFinancialWrite").asBoolean()).isFalse();

        // decision = null -> 400 VALIDATION_FAILED
        mockMvc.perform(manager1.withAuth(post("/api/v1/ai/jobs/" + rejectJobId + "/confirmation"))
                        .header("Idempotency-Key", uniqueKey("e2e-job-null-dec"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"reason\":\"missing decision\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        long rejectVersion = proposedJob1.at("/version").asLong();
        JsonNode rejected = data(manager1.withAuth(post("/api/v1/ai/jobs/" + rejectJobId + "/confirmation"))
                .header("Idempotency-Key", uniqueKey("e2e-job-do-rej"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", rejectVersion, "decision", "REJECT",
                        "reason", "人工驳回不生成待办"))));
        assertThat(rejected.at("/status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.at("/taskId").isNull()).isTrue();
        assertThat(queryInt("SELECT COUNT(*) FROM crm_audit_log WHERE tenant_id=1 "
                + "AND action_code='AI_JOB_REJECTED' AND target_id=?", Long.toString(rejectJobId))).isEqualTo(1);

        // Job 2: Create -> Execute -> Confirm CONFIRM -> taskId created
        long confirmJobId = data(manager1.withAuth(post("/api/v1/ai/jobs"))
                .header("Idempotency-Key", uniqueKey("e2e-job-conf"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("messageIds", List.of(msg2)))))
                .at("/id").asLong();

        assertThat(aiJobWorker.runOnce()).isGreaterThanOrEqualTo(1);
        long confirmVersion = data(get("/api/v1/ai/jobs/" + confirmJobId).cookie(manager1.cookies()))
                .at("/version").asLong();

        JsonNode confirmed = data(manager1.withAuth(post("/api/v1/ai/jobs/" + confirmJobId + "/confirmation"))
                .header("Idempotency-Key", uniqueKey("e2e-job-do-conf"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("version", confirmVersion, "decision", "CONFIRM",
                        "title", "E2E确认跟进水光针", "description", "联系客户确认时间"))));
        assertThat(confirmed.at("/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.at("/taskId").asLong()).isPositive();
    }

    @Test
    void mysqlSendFenceReleasesRetryableThenBlocksDuplicateAndSupportsEmergencyStopAndHumanTakeover()
            throws Exception {
        AuthCookies admin = login("admin");
        AuthCookies manager1 = login("manager1");

        mockMvc.perform(admin.withAuth(put("/api/v1/ai/config"))
                        .header("Idempotency-Key", uniqueKey("e2e-fence-ai-cfg"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("provider", "mock", "model", "mock-rules-v1",
                                "timeoutSeconds", 20, "enabled", true, "version", 0))))
                .andExpect(status().isOk());

        // Enable AI delegation policy 24h for team 1 & staff 2
        mockMvc.perform(admin.withAuth(put("/api/v1/ai/chat-delegation/policy"))
                        .header("Idempotency-Key", uniqueKey("e2e-del-pol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "enabled", true,
                                "minIntervalSeconds", 0,
                                "dailyLimit", 50,
                                "allowedStart", "00:00",
                                "allowedEnd", "23:59",
                                "zoneId", "Asia/Shanghai",
                                "blockedTerms", List.of(),
                                "scopes", List.of(
                                        Map.of("scopeType", "TEAM", "scopeId", 1, "enabled", true),
                                        Map.of("scopeType", "STAFF", "scopeId", 2, "enabled", true)),
                                "version", 0,
                                "reason", "E2E发送栅栏测试"))))
                .andExpect(status().isOk());

        long customerId = data(manager1.withAuth(post("/api/v1/customers"))
                .header("Idempotency-Key", uniqueKey("e2e-fence-cust"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("nickname", shortUuid("FenceCust")))))
                .at("/id").asLong();

        long msg1 = insertReadableTextWecomMessage(customerId, 2L, "请问可以预约吗");

        when(aiWorkerClient.extract(anyLong(), anyLong(), anyList(), anyList(), anyList(), any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<AiWorkerClient.MessageInput> inputs = inv.getArgument(2);
                    List<String> evidenceIds = inputs.stream().map(AiWorkerClient.MessageInput::id).toList();
                    return new AiWorkerClient.ExtractionResult("mock", "mock-rules-v1",
                            new AiWorkerClient.InsightData("APPOINTMENT_INTENT", List.of(), null, null,
                                    null, evidenceIds, new BigDecimal("0.90"), List.of("OFFER_HUMAN_SUPPORT"),
                                    "UNVERIFIED", false, "可预约", "您好，随时为您安排预约", null, List.of()));
                });

        var run1 = delegationService.receive(1L, msg1);
        assertThat(run1.status()).isEqualTo("QUEUED");

        // First send -> RETRYABLE => RETRY_SEND and fence in_flight=0, finished_at IS NULL (H-BUG-07)
        when(externalContactSender.mode()).thenReturn("OFFICIAL");
        when(externalContactSender.send(any(ExternalContactMessageCommand.class)))
                .thenReturn(new WecomSendResult(WecomSendResult.Status.RETRYABLE, "OFFICIAL", null, "RATE_LIMIT"))
                .thenReturn(new WecomSendResult(WecomSendResult.Status.SENT, "OFFICIAL", "msg-sent-001", null));

        delegationWorker.runOnce();
        assertThat(queryString("SELECT status FROM crm_ai_chat_delegation_run WHERE id=?", run1.id()))
                .isEqualTo("RETRY_SEND");
        Map<String, Object> fenceAfterRetryable = jdbcTemplate.queryForMap(
                "SELECT in_flight, finished_at, blocked FROM crm_ai_chat_send_fence WHERE tenant_id=1 AND run_id=?",
                run1.id());
        assertThat(asBool(fenceAfterRetryable.get("in_flight"))).isFalse();
        assertThat(fenceAfterRetryable.get("finished_at")).isNull();
        assertThat(asBool(fenceAfterRetryable.get("blocked"))).isFalse();

        // Advance next_attempt_at so worker retries immediately -> SENT, finished_at != NULL
        jdbcTemplate.update("UPDATE crm_ai_chat_delegation_run SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 5 SECOND) "
                + "WHERE id=?", run1.id());
        delegationWorker.runOnce();
        assertThat(queryString("SELECT status FROM crm_ai_chat_delegation_run WHERE id=?", run1.id()))
                .isEqualTo("SENT");
        Map<String, Object> fenceAfterSent = jdbcTemplate.queryForMap(
                "SELECT in_flight, finished_at FROM crm_ai_chat_send_fence WHERE tenant_id=1 AND run_id=?",
                run1.id());
        assertThat(asBool(fenceAfterSent.get("in_flight"))).isFalse();
        assertThat(fenceAfterSent.get("finished_at")).isNotNull();

        // Duplicate send attempt on already-completed run is blocked by SEND_ALREADY_COMPLETED
        String owner = shortUuid("dup-owner");
        jdbcTemplate.update("UPDATE crm_ai_chat_delegation_run SET status='SENDING', lease_owner=?, "
                + "lease_expires_at=DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 60 SECOND) WHERE id=?", owner, run1.id());
        delegationService.send(1L, run1.id(), owner);
        assertThat(queryString("SELECT status FROM crm_ai_chat_delegation_run WHERE id=?", run1.id()))
                .isEqualTo("SENDING");

        // HUMAN_TAKEOVER -> blocks subsequent run
        long ctrlVersion = data(get("/api/v1/ai/chat-delegation/customers/" + customerId + "/control")
                .cookie(manager1.cookies())).at("/version").asLong();
        mockMvc.perform(manager1.withAuth(put("/api/v1/ai/chat-delegation/customers/" + customerId + "/control"))
                        .header("Idempotency-Key", uniqueKey("e2e-del-takeover"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("mode", "HUMAN_TAKEOVER", "reason", "管家人工接管",
                                "version", ctrlVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("HUMAN_TAKEOVER"));

        long msg2 = insertReadableTextWecomMessage(customerId, 2L, "接管后消息");
        var run2 = delegationService.receive(1L, msg2);
        assertThat(run2.status()).isEqualTo("SKIPPED");
        assertThat(run2.reasonMasked()).isEqualTo("HUMAN_TAKEOVER");

        // Emergency stop -> blocks subsequent run
        long currentPolicyVersion = data(get("/api/v1/ai/chat-delegation/policy").cookie(admin.cookies()))
                .at("/version").asLong();
        mockMvc.perform(admin.withAuth(post("/api/v1/ai/chat-delegation/emergency-stop"))
                        .header("Idempotency-Key", uniqueKey("e2e-del-estop"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("stopped", true, "reason", "E2E紧急熔断",
                                "version", currentPolicyVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emergencyStopped").value(true));

        long msg3 = insertReadableTextWecomMessage(customerId, 2L, "熔断后消息");
        var run3 = delegationService.receive(1L, msg3);
        assertThat(run3.status()).isEqualTo("SKIPPED");
        assertThat(run3.reasonMasked()).isEqualTo("EMERGENCY_STOP");
    }

    @Test
    void liveJavaToPythonAiWorkerHttpContractInMockMode() throws Exception {
        Path aiWorkerDir = resolveAiWorkerDir();
        Path pythonBin = aiWorkerDir.resolve(".venv/bin/python");
        Assumptions.assumeTrue(Files.isExecutable(pythonBin), "apps/ai-worker/.venv/bin/python not present");

        int port = findFreePort();
        String internalToken = "e2e-internal-token-" + UUID.randomUUID();
        ProcessBuilder pb = new ProcessBuilder(
                pythonBin.toAbsolutePath().toString(),
                "-m", "uvicorn", "app.main:create_app", "--factory",
                "--host", "127.0.0.1", "--port", Integer.toString(port));
        pb.directory(aiWorkerDir.toFile());
        pb.environment().put("AI_PROVIDER", "mock");
        pb.environment().put("AI_INTERNAL_TOKEN", internalToken);
        pb.redirectErrorStream(true);

        Process workerProc = pb.start();
        try {
            String baseUrl = "http://127.0.0.1:" + port;
            awaitWorkerHealth(baseUrl, workerProc);

            AiWorkerClient liveAiClient = new AiWorkerClient(objectMapper, baseUrl, internalToken, 10_000L);
            KnowledgeWorkerClient liveKnowledgeClient = new KnowledgeWorkerClient(
                    objectMapper, baseUrl, internalToken, 10_000L);

            AiWorkerClient.ProviderConfig mockAiCfg = new AiWorkerClient.ProviderConfig(
                    "mock", "", "", "mock-rules-v1", 10);
            AiWorkerClient.ExtractionResult extraction = liveAiClient.extract(
                    101L, 202L,
                    List.of(new AiWorkerClient.MessageInput("m-1", "CUSTOMER", Instant.now(), "想预约水光针项目")),
                    List.of(new AiWorkerClient.ProjectInput("SGZ", "水光针")),
                    List.of(new AiWorkerClient.InstitutionInput("1", "示例机构")),
                    mockAiCfg);
            assertThat(extraction.provider()).isEqualTo("mock");
            assertThat(extraction.insight()).isNotNull();
            assertThat(extraction.insight().factStatus()).isEqualTo("UNVERIFIED");
            assertThat(extraction.insight().allowFinancialWrite()).isFalse();
            assertThat(extraction.insight().evidenceMessageIds()).containsExactly("m-1");

            AiWorkerClient.ReplyGenerationResult reply = liveAiClient.generateReply(
                    "sug-1", 202L, "APPOINTMENT",
                    Map.of("customer_name", "林女士", "project_name", "水光针", "institution_name", "示例机构"),
                    "请礼貌回复预约流程", mockAiCfg);
            assertThat(reply.provider()).isEqualTo("mock");
            assertThat(reply.suggestion()).isNotNull();
            assertThat(reply.suggestion().reply()).isNotBlank();
            assertThat(reply.suggestion().factStatus()).isEqualTo("UNVERIFIED");

            String traceId = "e2e-trace-" + UUID.randomUUID();
            liveKnowledgeClient.testConnection(
                    new KnowledgeWorkerClient.ProviderConfig("mock", "", "", 10), 1L, traceId);

            KnowledgeWorkerClient.DatasetProviderConfig dsCfg = new KnowledgeWorkerClient.DatasetProviderConfig(
                    "mock", "", "", "ds-mock-1", 10);
            KnowledgeWorkerClient.DocumentSyncResult synced = liveKnowledgeClient.syncDocument(
                    dsCfg,
                    new KnowledgeWorkerClient.DocumentSyncInput("job-1", "doc.md", "# 知识正文",
                            new KnowledgeWorkerClient.DocumentMetadata("1", "10", "20", 1, "GUIDE", null, null,
                                    "STAFF_INTERNAL", "a".repeat(64)),
                            null),
                    traceId + "-sync");
            assertThat(synced.requestId()).isEqualTo(traceId + "-sync");
            assertThat(synced.indexingStatus()).isEqualTo("AVAILABLE");

            KnowledgeWorkerClient.DocumentSyncResult statusRes = liveKnowledgeClient.documentStatus(
                    dsCfg, synced.documentId(), synced.batch(), traceId + "-status");
            assertThat(statusRes.requestId()).isEqualTo(traceId + "-status");
            assertThat(statusRes.indexingStatus()).isEqualTo("AVAILABLE");

            liveKnowledgeClient.disableDocument(dsCfg, synced.documentId(), traceId + "-disable");
        } finally {
            workerProc.destroy();
            if (!workerProc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                workerProc.destroyForcibly();
            }
        }
    }

    private long insertReadableTextWecomMessage(long customerId, long staffUserId, String text) throws Exception {
        String suffix = shortUuid("aimsg").replace("-", "");
        String corpId = "E2E-AI-CORP";
        String extUserId = "wm-ai-" + suffix;
        long seq = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L) + 1L;
        String objKey = "e2e/wecom/" + suffix + ".enc";
        byte[] encryptedBytes = cipher.encrypt(json(Map.of("text", text))).getBytes(StandardCharsets.UTF_8);
        wecomObjectStore.put(objKey, encryptedBytes);
        String sha256 = sha256Hex(encryptedBytes);

        jdbcTemplate.update("INSERT INTO crm_wecom_staff_account (tenant_id,corp_id,wecom_userid,staff_user_id,enabled) "
                + "VALUES (1,?,'staff-2',?,1) ON DUPLICATE KEY UPDATE enabled=1", corpId, staffUserId);
        jdbcTemplate.update("INSERT INTO crm_wecom_archive_member (tenant_id,corp_id,staff_user_id,enabled,source_code,evidence_ref) "
                + "VALUES (1,?,?,'1','MOCK','e2e-ai') ON DUPLICATE KEY UPDATE enabled=1", corpId, staffUserId);
        jdbcTemplate.update("INSERT INTO crm_wecom_contact (tenant_id,corp_id,external_userid,staff_user_id,customer_id,"
                        + "consent_status,consent_evidence_ref,follow_status,link_type,link_reason,created_by) "
                        + "VALUES (1,?,?,?,?,'GRANTED','synthetic://consent','ACTIVE','MANUAL','E2E',1)",
                corpId, extUserId, staffUserId, customerId);

        jdbcTemplate.update("INSERT INTO crm_wecom_message (tenant_id,corp_id,msg_id,archive_scope,archive_seq,"
                        + "conversation_type,external_userid,staff_user_id,customer_id,conversation_id,"
                        + "message_type,sender_type,sent_at,content_object_key,content_sha256,"
                        + "consent_snapshot,consent_evidence_ref,status) "
                        + "VALUES (1,?,?,'MOCK_ARCHIVE',?,'DIRECT',?,?,?,?, 'TEXT','CUSTOMER',?, ?,?, "
                        + "'GRANTED','synthetic://consent','STORED')",
                corpId, "E2E-AIMSG-" + suffix, seq, extUserId, staffUserId, customerId, extUserId,
                Timestamp.from(Instant.now().minusSeconds(30)), objKey, sha256);
        return queryLong("SELECT id FROM crm_wecom_message WHERE tenant_id=1 AND corp_id=? AND msg_id=?",
                corpId, "E2E-AIMSG-" + suffix);
    }

    private Path resolveAiWorkerDir() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("apps/ai-worker"))) {
            return cwd.resolve("apps/ai-worker");
        }
        if (Files.isDirectory(cwd.resolve("../ai-worker"))) {
            return cwd.resolve("../ai-worker").normalize();
        }
        return cwd.resolve("apps/ai-worker");
    }

    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void awaitWorkerHealth(String baseUrl, Process proc) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        for (int i = 0; i < 60; i++) {
            if (!proc.isAlive()) {
                String out = new String(proc.getInputStream().readAllBytes());
                throw new AssertionError("uvicorn exited early (" + proc.exitValue() + "): " + out);
            }
            try {
                HttpResponse<String> resp = client.send(
                        HttpRequest.newBuilder(URI.create(baseUrl + "/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode body = objectMapper.readTree(resp.body());
                    assertThat(body.at("/status").asText()).isEqualTo("ok");
                    return;
                }
            } catch (IOException ignored) {
                // wait for uvicorn startup
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for uvicorn /health at " + baseUrl);
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number num) {
            return num.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
