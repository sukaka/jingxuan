package com.whale.crm.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
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
import java.util.Objects;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.ai.delegation.kafka.enabled", havingValue = "true")
public class AiChatDelegationEventConsumer {
    public static final String GROUP = "crm-ai-chat-delegation";
    private static final String EVENT_TYPE = "wecom.message.archived.v1";
    private static final Set<String> ENVELOPE_REQUIRED = Set.of("event_id", "event_type", "event_version",
            "tenant_id", "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "payload");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("event_id", "event_type", "event_version",
            "tenant_id", "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "causation_id",
            "actor_id", "payload");
    private static final Set<String> PAYLOAD_REQUIRED = Set.of("messageRef", "corpId", "msgId", "archiveSeq",
            "contentObjectKey", "sentAt");
    private static final Set<String> PAYLOAD_FIELDS = Set.of("messageRef", "corpId", "msgId", "archiveSeq",
            "conversationId", "customerId", "contentObjectKey", "sentAt");

    private final AiChatDelegationService service;
    private final WecomRepository wecomRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public AiChatDelegationEventConsumer(AiChatDelegationService service, WecomRepository wecomRepository,
                                         JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                         PlatformTransactionManager transactionManager) {
        this.service = service;
        this.wecomRepository = wecomRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, groupId = GROUP,
            containerFactory = "aiChatDelegationKafkaListenerContainerFactory")
    public void listen(String message, Acknowledgment acknowledgment) {
        consume(message);
        acknowledgment.acknowledge();
    }

    public void consume(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            validateEnvelope(envelope);
            if (!EVENT_TYPE.equals(envelope.path("event_type").asText())) {
                return;
            }
            long tenantId = positive(envelope, "tenant_id");
            String eventId = requiredText(envelope, "event_id", 192);
            JsonNode payload = envelope.path("payload");
            requireFields(payload, PAYLOAD_REQUIRED, PAYLOAD_FIELDS);
            long messageId = positive(payload, "messageRef");
            requireAggregate(envelope, messageId);
            String corpId = requiredText(payload, "corpId", 96);
            String msgId = requiredText(payload, "msgId", 192);
            long archiveSeq = positive(payload, "archiveSeq");
            String objectKey = requiredText(payload, "contentObjectKey", 255);
            instant(payload.path("sentAt").asText());
            transactions.executeWithoutResult(status -> {
                if (!inbox(tenantId, eventId)) {
                    return;
                }
                WecomMessage source = wecomRepository.findMessageById(tenantId, messageId)
                        .orElseThrow(() -> new IllegalArgumentException("AI托管企微来源消息不存在"));
                if (!corpId.equals(source.corpId()) || !msgId.equals(source.msgId())
                        || source.archiveSeq() == null || source.archiveSeq() != archiveSeq
                        || !objectKey.equals(source.contentObjectKey())
                        || (payload.has("customerId") && !payload.path("customerId").isNull()
                        && !Objects.equals(payload.path("customerId").asLong(), source.customerId()))) {
                    throw new IllegalArgumentException("AI托管企微事件与归档来源不一致");
                }
                service.receive(tenantId, messageId);
            });
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("AI托管事件结构或来源记录无效", ex);
        }
    }

    private boolean inbox(long tenantId, String eventId) {
        try {
            jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id,processed_at) "
                    + "VALUES (?,?,?,CURRENT_TIMESTAMP(3))", tenantId, GROUP, eventId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    private void validateEnvelope(JsonNode envelope) {
        requireFields(envelope, ENVELOPE_REQUIRED, ENVELOPE_FIELDS);
        String eventId = requiredText(envelope, "event_id", 192);
        if (!eventId.equals(requiredText(envelope, "correlation_id", 192))
                || envelope.path("event_version").asInt() != 1) {
            throw new IllegalArgumentException("AI托管事件版本或correlation_id无效");
        }
        instant(envelope.path("occurred_at").asText());
    }

    private void requireAggregate(JsonNode envelope, long messageId) {
        if (!"wecom".equals(envelope.path("aggregate_type").asText())
                || !Objects.equals(envelope.path("aggregate_id").asText(), Long.toString(messageId))) {
            throw new IllegalArgumentException("AI托管事件aggregate无效");
        }
    }

    private void requireFields(JsonNode node, Set<String> required, Set<String> allowed) {
        if (node == null || !node.isObject() || !required.stream().allMatch(node::has)) {
            throw new IllegalArgumentException("AI托管事件必填字段缺失");
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new IllegalArgumentException("AI托管事件包含契约外字段");
            }
        }
    }

    private long positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("AI托管事件" + field + "必须为正整数");
        }
        return value.asLong();
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("AI托管事件" + field + "无效");
        }
        return value;
    }

    private void instant(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("AI托管事件时间无效", ex);
        }
    }
}
