package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.KnowledgeWorkerClient;
import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.platform.support.AbstractMysqlIT;
import com.whale.crm.platform.support.KafkaTestContainer;
import com.whale.crm.trading.application.OutboxRelayService;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
        "app.outbox.relay.enabled=true", "app.outbox.relay.delay-ms=60000",
        "app.ai.knowledge.sync.kafka.enabled=true",
        "app.ai.knowledge.sync.worker.enabled=true", "app.ai.knowledge.sync.worker.delay-ms=100"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
class KnowledgeBrokerReliabilityIT {
    private static final GenericContainer<?> KAFKA = KafkaTestContainer.start();
    @ServiceConnection
    private static final MySQLContainer<?> DATABASE = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("k01_reliability")
            .withUsername("k01")
            .withPassword("k01-test-only")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");
    private static final String TEXT = "# K01 合成知识文本\n不包含客户数据";

    static {
        DATABASE.start();
    }

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> KafkaTestContainer.bootstrapServers(KAFKA));
    }

    @MockitoBean PrivateObjectStorage storage;
    @MockitoBean com.whale.crm.file.application.ClamAvScanner scanner;
    @MockitoBean KnowledgeWorkerClient workerClient;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired com.whale.crm.platform.auth.application.CaptchaService captchaService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OutboxRelayService relayService;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listenerRegistry;

    private long fileId;
    private long materialId;
    private long revisionId;
    private String objectKey;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("k01_reliability");
        objects.clear();
        when(scanner.scan(any())).thenReturn(com.whale.crm.file.application.ClamAvScanner.ScanResult.CLEAN);
        when(storage.getObject(anyString())).thenAnswer(invocation -> {
            byte[] content = objects.get(invocation.getArgument(0));
            if (content == null) {
                throw new IllegalStateException("测试对象不存在");
            }
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
        when(workerClient.syncDocument(any(), any(), anyString())).thenAnswer(invocation ->
                new KnowledgeWorkerClient.DocumentSyncResult(invocation.getArgument(2), "mock", "doc-k01",
                        "batch-k01", "AVAILABLE", null));
        jdbcTemplate.update("DELETE FROM crm_outbox_event WHERE tenant_id=1 AND aggregate_type='knowledge_document'");
        jdbcTemplate.update("DELETE FROM crm_inbox_event WHERE tenant_id=1 AND consumer_group='crm-knowledge-sync'");
        jdbcTemplate.update("DELETE FROM crm_idempotency_record WHERE tenant_id=1 AND scope_code='ai.knowledge.publish'");
        jdbcTemplate.update("DELETE FROM crm_knowledge_sync_job WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_knowledge_document WHERE tenant_id=1");
        jdbcTemplate.update("DELETE FROM crm_dify_knowledge_config WHERE tenant_id=1");
        jdbcTemplate.update("INSERT INTO crm_dify_knowledge_config (tenant_id,provider,dataset_id,sync_enabled,"
                + "timeout_seconds,enabled,connection_status) VALUES (1,'mock','mock-k01',TRUE,5,TRUE,'UNVERIFIED')");
        createSource();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lz4", "snappy"})
    void realBrokerDeduplicatesReplayAndRejectsInvalidEventToDlq(String compression) throws Exception {
        kafkaTemplate.getProducerFactory().updateConfigs(Map.of(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression));
        assertThat(kafkaTemplate.getProducerFactory().getConfigurationProperties())
                .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression);
        awaitListenerReady();
        AbstractMysqlIT.AuthCookies admin = login("admin");
        String response = mockMvc.perform(admin.withAuth(post("/api/v1/ai/knowledge/documents/publications"))
                        .header("Idempotency-Key", "k01-publish-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "materialId", materialId, "revisionId", revisionId, "version", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andReturn().getResponse().getContentAsString();
        long jobId = objectMapper.readTree(response).at("/data/id").asLong();
        long documentId = objectMapper.readTree(response).at("/data/documentId").asLong();
        Map<String, Object> outbox = jdbcTemplate.queryForMap("SELECT id,event_id FROM crm_outbox_event "
                + "WHERE tenant_id=1 AND aggregate_type='knowledge_document' AND aggregate_id=?", Long.toString(jobId));
        long outboxId = ((Number) outbox.get("id")).longValue();
        String eventId = (String) outbox.get("event_id");

        assertThat(relayService.relayOnce()).isGreaterThanOrEqualTo(1);
        Map<String, Object> first = awaitJob(jobId, "SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_document WHERE id=?",
                String.class, documentId)).isEqualTo("ACTIVE");
        assertThat(inboxCount(eventId)).isEqualTo(1);
        assertThat(((Number) first.get("attempt_count")).intValue()).isEqualTo(1);

        for (int replay = 0; replay < 2; replay++) {
            assertThat(jdbcTemplate.update("UPDATE crm_outbox_event SET status='NEW',claim_owner=NULL,"
                    + "claim_expires_at=NULL,next_retry_at=NULL WHERE id=? AND status='SENT'", outboxId)).isEqualTo(1);
            assertThat(relayService.relayOnce()).isGreaterThanOrEqualTo(1);
            Thread.sleep(300);
        }

        Map<String, Object> afterReplay = jdbcTemplate.queryForMap(
                "SELECT status,version,attempt_count FROM crm_knowledge_sync_job WHERE id=?", jobId);
        assertThat(afterReplay).containsExactlyInAnyOrderEntriesOf(first);
        assertThat(inboxCount(eventId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_knowledge_sync_job WHERE tenant_id=1",
                Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_outbox_event WHERE tenant_id=1 "
                + "AND aggregate_type='knowledge_document' AND aggregate_id=?", Long.class, Long.toString(jobId)))
                .isEqualTo(1);

        String invalidKey = "k01-invalid-contract";
        String invalidMessage = """
                {"event_id":"k01-invalid-contract","event_type":"knowledge.sync.requested.v1","event_version":1,
                "tenant_id":1,"aggregate_type":"knowledge_document","aggregate_id":"999999",
                "correlation_id":"k01-invalid-contract","payload":{"jobId":999999,"documentId":999999}}
                """;
        kafkaTemplate.send(OutboxKafkaConfiguration.KNOWLEDGE_SYNC_TOPIC, invalidKey, invalidMessage)
                .get(5, TimeUnit.SECONDS);

        ConsumerRecord<String, String> dlq = awaitDlq(invalidKey);
        assertThat(dlq.value()).isEqualTo(invalidMessage);
        assertThat(header(dlq, "kafka_dlt-original-topic")).isEqualTo(OutboxKafkaConfiguration.KNOWLEDGE_SYNC_TOPIC);
        assertThat(ByteBuffer.wrap(dlq.headers().lastHeader("kafka_dlt-original-partition").value()).getInt())
                .isGreaterThanOrEqualTo(0);
        assertThat(ByteBuffer.wrap(dlq.headers().lastHeader("kafka_dlt-original-offset").value()).getLong())
                .isGreaterThanOrEqualTo(0);
        assertThat(header(dlq, "kafka_dlt-exception-message")).isNotBlank();
        assertThat(inboxCount(invalidKey)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM crm_knowledge_sync_job WHERE id=?",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM crm_knowledge_document WHERE id=?",
                String.class, documentId)).isEqualTo("ACTIVE");
    }

    private void awaitListenerReady() throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            boolean ready = listenerRegistry.getListenerContainers().stream()
                    .allMatch(container -> container.isRunning() && !container.getAssignedPartitions().isEmpty());
            if (ready) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka Listener 分区分配未完成");
    }

    private Map<String, Object> awaitJob(long jobId, String expected) throws InterruptedException {
        Map<String, Object> row = Map.of();
        for (int attempt = 0; attempt < 300; attempt++) {
            row = jdbcTemplate.queryForMap("SELECT status,version,attempt_count FROM crm_knowledge_sync_job WHERE id=?", jobId);
            if (expected.equals(row.get("status"))) {
                return row;
            }
            Thread.sleep(100);
        }
        System.out.println("K01-DIAG-OUTBOX=" + jdbcTemplate.queryForMap(
                "SELECT status,last_error,claim_owner,claim_expires_at,next_retry_at,version FROM crm_outbox_event "
                        + "WHERE tenant_id=1 AND aggregate_type='knowledge_document' AND aggregate_id=?", Long.toString(jobId)));
        try {
            ConsumerRecord<String, String> rejected = awaitDlq("knowledge.sync.requested.v1:" + jobId);
            System.out.println("K01-DIAG-DLQ=" + header(rejected, "kafka_dlt-exception-message"));
        } catch (AssertionError error) {
            System.out.println(error.getMessage());
        }
        assertThat(row.get("status")).as("等待知识任务进入 %s，最终状态为 %s", expected, row).isEqualTo(expected);
        return row;
    }

    private ConsumerRecord<String, String> awaitDlq(String key) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KafkaTestContainer.bootstrapServers(KAFKA));
        properties.put("group.id", "k01-dlq-inspector");
        properties.put("client.id", "k01-dlq-inspector-" + UUID.randomUUID());
        properties.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("crm.dlq.knowledge.sync.v1"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("非法事件未进入真实 DLQ: " + key);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        assertThat(header).as("DLQ header " + name).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private long inboxCount(String eventId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_inbox_event WHERE tenant_id=1 "
                + "AND consumer_group='crm-knowledge-sync' AND event_id=?", Long.class, eventId);
    }

    private void createSource() throws Exception {
        fileId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id),990000)+1 FROM crm_file_object WHERE id>=990000", Long.class);
        materialId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id),990000)+1 FROM crm_material WHERE id>=990000", Long.class);
        revisionId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id),990000)+1 FROM crm_material_revision WHERE id>=990000", Long.class);
        objectKey = "k01-source-" + fileId;
        byte[] bytes = TEXT.getBytes(StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        jdbcTemplate.update("INSERT INTO crm_file_object (id,tenant_id,object_key,original_name,mime_type,size_bytes,"
                        + "sha256,classification,institution_id,scan_status,uploaded_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                fileId, 1L, objectKey, "k01.md", "text/markdown", bytes.length, hash,
                "MATERIAL", 1L, "CLEAN", 1L);
        jdbcTemplate.update("INSERT INTO crm_material (id,tenant_id,material_no,title,category_code,institution_id,"
                        + "review_status,created_by) VALUES (?,?,?,?,?,?,?,?)",
                materialId, 1L, "K01-MATERIAL-" + materialId, "K01 合成素材", "GUIDE", 1L, "APPROVED", 1L);
        jdbcTemplate.update("INSERT INTO crm_material_revision (id,tenant_id,material_id,revision_no,title,category_code,"
                        + "institution_id,file_id,status,created_by,version) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                revisionId, 1L, materialId, 1, "K01 合成素材", "GUIDE", 1L, fileId, "APPROVED", 1L, 2L);
        jdbcTemplate.update("UPDATE crm_material SET current_revision_id=? WHERE id=?", revisionId, materialId);
        objects.put(objectKey, bytes);
    }

    private AbstractMysqlIT.AuthCookies login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload(username, "Dev@12345")))
                .andExpect(status().isOk())
                .andReturn();
        return new AbstractMysqlIT.AuthCookies(cookieValue(result, "CRM_TOKEN"), cookieValue(result, "CRM_CSRF"));
    }

    private String loginPayload(String username, String password) throws Exception {
        var captcha = captchaService.issue();
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password,
                "captchaId", captcha.captchaId(), "captchaAnswer", captcha.answer()));
    }

    private String cookieValue(MvcResult result, String name) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                String value = header.substring((name + "=").length());
                int semicolon = value.indexOf(';');
                return semicolon >= 0 ? value.substring(0, semicolon) : value;
            }
        }
        throw new AssertionError("响应缺少 Cookie: " + name);
    }
}
