package com.whale.crm.notification.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
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
@ConditionalOnProperty(name = "app.notification.kafka.enabled", havingValue = "true")
public class NotificationEventConsumer {
    public static final String GROUP = "crm-notification-request";
    private static final Set<String> ENVELOPE_REQUIRED = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "payload");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("event_id", "event_type", "event_version", "tenant_id",
            "aggregate_type", "aggregate_id", "occurred_at", "correlation_id", "causation_id", "actor_id", "payload");

    private final NotificationRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    @Autowired
    public NotificationEventConsumer(NotificationRepository repository, JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this(repository, jdbcTemplate, objectMapper, new TransactionTemplate(transactionManager));
    }

    NotificationEventConsumer(NotificationRepository repository, JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper, TransactionTemplate transactions) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    @KafkaListener(topics = OutboxKafkaConfiguration.NOTIFICATION_REQUEST_TOPIC, groupId = GROUP,
            containerFactory = "notificationKafkaListenerContainerFactory")
    public void listen(String message, Acknowledgment acknowledgment) {
        consume(message);
        acknowledgment.acknowledge();
    }

    public void consume(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            validateContract(envelope);
            long tenantId = positive(envelope, "tenant_id");
            long notificationId = positive(envelope.path("payload"), "notificationId");
            String eventId = envelope.path("event_id").asText();
            if (!NotificationRequestService.EVENT_TYPE.equals(envelope.path("event_type").asText())
                    || envelope.path("event_version").asInt() != 1
                    || !NotificationRequestService.AGGREGATE_TYPE.equals(envelope.path("aggregate_type").asText())
                    || !envelope.path("aggregate_id").asText().equals(Long.toString(notificationId))) {
                throw new IllegalArgumentException("通知事件信封或 aggregate_id 无效");
            }
            transactions.executeWithoutResult(status -> {
                try {
                    jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id,processed_at) "
                            + "VALUES (?,?,?,CURRENT_TIMESTAMP(3))", tenantId, GROUP, eventId);
                } catch (DuplicateKeyException ex) {
                    return;
                }
                repository.findById(tenantId, notificationId)
                        .orElseThrow(() -> new IllegalArgumentException("通知事件对应的逻辑通知不存在"));
                repository.activateDeliveries(tenantId, notificationId);
            });
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("通知事件结构无效", ex);
        }
    }

    private void validateContract(JsonNode envelope) {
        if (envelope == null || !envelope.isObject() || !hasRequiredFields(envelope, ENVELOPE_REQUIRED)
                || !hasOnlyFields(envelope, ENVELOPE_FIELDS) || !envelope.path("payload").isObject()
                || !hasRequiredFields(envelope.path("payload"), Set.of("notificationId"))
                || !hasOnlyFields(envelope.path("payload"), Set.of("notificationId"))) {
            throw new IllegalArgumentException("通知事件字段或 payload 不符合契约");
        }
        String eventId = envelope.path("event_id").asText();
        String correlationId = envelope.path("correlation_id").asText();
        if (eventId.isBlank() || eventId.length() > 192 || !eventId.equals(correlationId)) {
            throw new IllegalArgumentException("通知事件 correlation_id 无效");
        }
        try {
            Instant.parse(envelope.path("occurred_at").asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("通知事件 occurred_at 无效");
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
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("通知事件 " + field + " 必须为正整数");
        }
        return value.asLong();
    }
}
