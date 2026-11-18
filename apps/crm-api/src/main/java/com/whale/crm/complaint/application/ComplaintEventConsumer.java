package com.whale.crm.complaint.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.domain.Followup;
import com.whale.crm.customer.domain.FollowupRepository;
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
@ConditionalOnProperty(name = "app.complaint.kafka.enabled", havingValue = "true")
public class ComplaintEventConsumer {
    public static final String WECOM_GROUP = "crm-complaint-wecom-detection";
    public static final String FOLLOWUP_GROUP = "crm-complaint-followup-detection";
    public static final String WECOM_EVENT = "wecom.message.archived.v1";
    public static final String FOLLOWUP_EVENT = "followup.recorded.v1";

    private static final Set<String> ENVELOPE_REQUIRED = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "payload");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "causation_id", "actor_id", "payload");
    private static final Set<String> WECOM_REQUIRED = Set.of("messageRef", "corpId", "msgId", "archiveSeq",
            "contentObjectKey", "sentAt");
    private static final Set<String> WECOM_FIELDS = Set.of("messageRef", "corpId", "msgId", "archiveSeq",
            "conversationId", "customerId", "contentObjectKey", "sentAt");
    private static final Set<String> FOLLOWUP_REQUIRED = Set.of("followupId", "customerId", "sourceVersion",
            "staffUserId", "contactAt");
    private static final Set<String> FOLLOWUP_FIELDS = Set.of("followupId", "customerId", "sourceVersion",
            "staffUserId", "contactAt");

    private final ComplaintDetectionService detectionService;
    private final WecomRepository wecomRepository;
    private final FollowupRepository followupRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ComplaintEventConsumer(ComplaintDetectionService detectionService, WecomRepository wecomRepository,
                                  FollowupRepository followupRepository, JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.detectionService = detectionService;
        this.wecomRepository = wecomRepository;
        this.followupRepository = followupRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC, groupId = WECOM_GROUP,
            containerFactory = "complaintKafkaListenerContainerFactory")
    public void listenWecom(String message, Acknowledgment acknowledgment) {
        consumeWecom(message);
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = "${app.complaint.followup.topic:crm.followup.events.v1}", groupId = FOLLOWUP_GROUP,
            containerFactory = "complaintKafkaListenerContainerFactory")
    public void listenFollowup(String message, Acknowledgment acknowledgment) {
        consumeFollowup(message);
        acknowledgment.acknowledge();
    }

    public void consumeWecom(String message) {
        consume(message, WECOM_EVENT, WECOM_GROUP);
    }

    public void consumeFollowup(String message) {
        consume(message, FOLLOWUP_EVENT, FOLLOWUP_GROUP);
    }

    private void consume(String message, String expectedType, String group) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            if (envelope == null || !envelope.isObject()) {
                throw new IllegalArgumentException("客诉事件信封无效");
            }
            if (!expectedType.equals(envelope.path("event_type").asText())) {
                return;
            }
            validateEnvelope(envelope);
            long tenantId = positive(envelope, "tenant_id");
            String eventId = envelope.path("event_id").asText();
            if (WECOM_EVENT.equals(expectedType)) {
                consumeWecom(tenantId, eventId, group, envelope);
            } else {
                consumeFollowup(tenantId, eventId, group, envelope);
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("客诉事件结构或来源记录无效", ex);
        }
    }

    private void consumeWecom(long tenantId, String eventId, String group, JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        requirePayload(payload, WECOM_REQUIRED, WECOM_FIELDS);
        long messageId = positive(payload, "messageRef");
        requireAggregate(envelope, "wecom", messageId);
        String corpId = requiredText(payload, "corpId", 96);
        String msgId = requiredText(payload, "msgId", 192);
        long archiveSeq = positive(payload, "archiveSeq");
        String objectKey = requiredText(payload, "contentObjectKey", 255);
        instant(payload.path("sentAt").asText());
        transactions.executeWithoutResult(status -> {
            if (!inbox(tenantId, group, eventId)) {
                return;
            }
            WecomMessage message = wecomRepository.findMessageById(tenantId, messageId)
                    .orElseThrow(() -> new IllegalArgumentException("企微来源消息不存在"));
            if (!corpId.equals(message.corpId()) || !msgId.equals(message.msgId())
                    || message.archiveSeq() == null || message.archiveSeq() != archiveSeq
                    || !objectKey.equals(message.contentObjectKey())
                    || (payload.has("customerId") && !payload.path("customerId").isNull()
                    && !Objects.equals(payload.path("customerId").asLong(), message.customerId()))) {
                throw new IllegalArgumentException("企微事件与已归档消息不一致");
            }
            detectionService.detectWecom(tenantId, messageId, null);
        });
    }

    private void consumeFollowup(long tenantId, String eventId, String group, JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        requirePayload(payload, FOLLOWUP_REQUIRED, FOLLOWUP_FIELDS);
        long followupId = positive(payload, "followupId");
        requireAggregate(envelope, "followup", followupId);
        positive(payload, "customerId");
        positive(payload, "staffUserId");
        if (!payload.path("sourceVersion").isIntegralNumber() || payload.path("sourceVersion").asLong(-1) < 0) {
            throw new IllegalArgumentException("跟进事件 sourceVersion 必须为非负整数");
        }
        instant(payload.path("contactAt").asText());
        transactions.executeWithoutResult(status -> {
            if (!inbox(tenantId, group, eventId)) {
                return;
            }
            Followup followup = followupRepository.findById(tenantId, followupId)
                    .orElseThrow(() -> new IllegalArgumentException("跟进来源记录不存在"));
            if (!Objects.equals(payload.path("customerId").asLong(), followup.getCustomerId())
                    || !Objects.equals(payload.path("staffUserId").asLong(), followup.getOwnerStaffId())) {
                throw new IllegalArgumentException("跟进事件客户或员工与来源记录不一致");
            }
            detectionService.detectFollowup(tenantId, followupId, null);
        });
    }

    private boolean inbox(long tenantId, String group, String eventId) {
        try {
            jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id,processed_at) "
                    + "VALUES (?,?,?,CURRENT_TIMESTAMP(3))", tenantId, group, eventId);
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    private void validateEnvelope(JsonNode envelope) {
        requirePayload(envelope, ENVELOPE_REQUIRED, ENVELOPE_FIELDS);
        String eventId = requiredText(envelope, "event_id", 64);
        if (!eventId.equals(requiredText(envelope, "correlation_id", 64))
                || envelope.path("event_version").asInt() != 1) {
            throw new IllegalArgumentException("客诉事件版本或 correlation_id 无效");
        }
        instant(envelope.path("occurred_at").asText());
    }

    private void requireAggregate(JsonNode envelope, String aggregateType, long aggregateId) {
        if (!aggregateType.equals(envelope.path("aggregate_type").asText())
                || !Objects.equals(envelope.path("aggregate_id").asText(), Long.toString(aggregateId))) {
            throw new IllegalArgumentException("客诉事件 aggregate_type 或 aggregate_id 无效");
        }
    }

    private void requirePayload(JsonNode node, Set<String> required, Set<String> allowed) {
        if (node == null || !node.isObject() || !required.stream().allMatch(node::has)) {
            throw new IllegalArgumentException("客诉事件必填字段缺失");
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw new IllegalArgumentException("客诉事件包含契约外字段");
            }
        }
    }

    private long positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("客诉事件 " + field + " 必须为正整数");
        }
        return value.asLong();
    }

    private String requiredText(JsonNode node, String field, int maxLength) {
        String value = node.path(field).asText();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException("客诉事件 " + field + " 无效");
        }
        return value;
    }

    private void instant(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("客诉事件时间无效", ex);
        }
    }
}
