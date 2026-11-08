package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.ai.knowledge.sync.kafka.enabled", havingValue = "true")
public class KnowledgeSyncConsumer {
    private static final String GROUP = "crm-knowledge-sync";
    private static final Set<String> ENVELOPE_REQUIRED = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "payload");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "causation_id", "actor_id", "payload");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("jobId", "documentId");
    private final KnowledgeRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public KnowledgeSyncConsumer(KnowledgeRepository repository, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = OutboxKafkaConfiguration.KNOWLEDGE_SYNC_TOPIC, groupId = GROUP,
            containerFactory = "knowledgeKafkaListenerContainerFactory")
    public void listen(String message, Acknowledgment acknowledgment) {
        consume(message);
        acknowledgment.acknowledge();
    }

    public void consume(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            validateContract(envelope);
            long tenantId = positive(envelope, "tenant_id");
            long jobId = positive(envelope.at("/payload"), "jobId");
            long documentId = positive(envelope.at("/payload"), "documentId");
            String eventId = envelope.path("event_id").asText();
            if (eventId.isBlank() || eventId.length() > 192
                    || !"knowledge.sync.requested.v1".equals(envelope.path("event_type").asText())
                    || envelope.path("event_version").asInt() != 1
                    || !"knowledge_document".equals(envelope.path("aggregate_type").asText())
                    || !envelope.path("aggregate_id").asText().equals(Long.toString(jobId))) {
                throw new IllegalArgumentException("知识事件信封或 aggregate_id 无效");
            }
            String finalEventId = eventId;
            transactions.executeWithoutResult(status -> {
                try {
                    jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id,processed_at) "
                            + "VALUES (?,?,?,CURRENT_TIMESTAMP(3))", tenantId, GROUP, finalEventId);
                } catch (DuplicateKeyException ex) {
                    return;
                }
                KnowledgeSyncJob job = repository.findSyncJobById(tenantId, jobId)
                        .orElseThrow(() -> new IllegalArgumentException("知识同步任务不存在"));
                if (job.documentId() != documentId) {
                    throw new IllegalArgumentException("知识事件 documentId 与任务不一致");
                }
                if (repository.findDocumentById(tenantId, documentId)
                        .map(document -> "DISABLED".equals(document.status())).orElse(false)) {
                    return;
                }
                if ("WAITING".equals(job.status())
                        && repository.markPending(tenantId, jobId, job.version()) != 1) {
                    throw new IllegalStateException("知识任务状态已变化");
                }
            });
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("知识事件结构无效", ex);
        }
    }

    private void validateContract(JsonNode envelope) {
        if (!envelope.isObject() || !hasRequiredFields(envelope, ENVELOPE_REQUIRED)
                || !hasOnlyFields(envelope, ENVELOPE_FIELDS)
                || !envelope.path("payload").isObject()
                || !hasRequiredFields(envelope.path("payload"), PAYLOAD_FIELDS)
                || !hasOnlyFields(envelope.path("payload"), PAYLOAD_FIELDS)) {
            throw new IllegalArgumentException("知识事件字段不符合契约");
        }
        String eventId = envelope.path("event_id").asText();
        String correlationId = envelope.path("correlation_id").asText();
        if (correlationId.isBlank() || correlationId.length() > 192 || !eventId.equals(correlationId)) {
            throw new IllegalArgumentException("知识事件 correlation_id 无效");
        }
        try {
            Instant.parse(envelope.path("occurred_at").asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("知识事件 occurred_at 无效");
        }
    }

    private boolean hasRequiredFields(JsonNode node, Set<String> required) {
        return required.stream().allMatch(node::has);
    }

    private boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private long positive(JsonNode node, String field) {
        long value = node.path(field).asLong(-1);
        if (value <= 0) {
            throw new IllegalArgumentException("知识事件 " + field + " 必须为正数");
        }
        return value;
    }
}
