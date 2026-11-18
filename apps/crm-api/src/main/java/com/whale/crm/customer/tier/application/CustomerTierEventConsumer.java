package com.whale.crm.customer.tier.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "app.customer-tier.kafka.enabled", havingValue = "true")
public class CustomerTierEventConsumer {
    public static final String GROUP = "crm-customer-tier-recalculation";
    private static final String CUSTOMER_TOPIC = OutboxKafkaConfiguration.CUSTOMER_EVENTS_TOPIC;
    private static final Set<String> FOLLOWUP_EVENTS = Set.of("followup.recorded.v1",
            "followup.created.v1", "followup.updated.v1", "followup.deleted.v1");

    private final CustomerTierRecalculationService recalculationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public CustomerTierEventConsumer(CustomerTierRecalculationService recalculationService, JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.recalculationService = recalculationService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(topics = {OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC,
            OutboxKafkaConfiguration.REFUND_EVENTS_TOPIC, OutboxKafkaConfiguration.FOLLOWUP_EVENTS_TOPIC,
            CUSTOMER_TOPIC}, groupId = GROUP)
    public void listen(String message) {
        consume(message);
    }

    public void consume(String message) {
        try {
            JsonNode envelope = objectMapper.readTree(message);
            validateEnvelope(envelope);
            long tenantId = positive(envelope, "tenant_id");
            long customerId = positive(envelope.path("payload"), "customerId");
            String eventId = envelope.path("event_id").asText();
            String eventType = envelope.path("event_type").asText();
            String trigger = triggerOf(eventType);
            Instant occurredAt = Instant.parse(envelope.path("occurred_at").asText());
            transactions.executeWithoutResult(status -> {
                try {
                    jdbcTemplate.update("INSERT INTO crm_inbox_event (tenant_id,consumer_group,event_id,processed_at) "
                            + "VALUES (?,?,?,CURRENT_TIMESTAMP(3))", tenantId, GROUP, eventId);
                } catch (DuplicateKeyException ex) {
                    return;
                }
                recalculationService.recalculate(tenantId, customerId, occurredAt, trigger,
                        "event:" + eventId, null);
            });
        } catch (BusinessException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("客户分级事件结构无效", ex);
        }
    }

    private void validateEnvelope(JsonNode envelope) {
        if (envelope == null || !envelope.isObject() || envelope.path("event_id").asText().isBlank()
                || envelope.path("event_version").asInt() != 1 || !envelope.path("payload").isObject()) {
            throw new IllegalArgumentException("客户分级事件信封无效");
        }
        try {
            Instant.parse(envelope.path("occurred_at").asText());
            triggerOf(envelope.path("event_type").asText());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("客户分级事件 occurred_at 无效", ex);
        }
    }

    private String triggerOf(String eventType) {
        return switch (eventType) {
            case "order.paid.v1", "payment.confirmed.v1" -> "ORDER_PAID";
            case "refund.succeeded.v1" -> "REFUND_SUCCEEDED";
            default -> {
                if (FOLLOWUP_EVENTS.contains(eventType)) {
                    yield "FOLLOWUP";
                }
                throw new IllegalArgumentException("不是客户分级支持的触发事件");
            }
        };
    }

    private long positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("客户分级事件 " + field + " 必须为正整数");
        }
        return value.asLong();
    }
}
