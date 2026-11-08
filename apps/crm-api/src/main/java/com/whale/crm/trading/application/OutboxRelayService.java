package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import com.whale.crm.trading.infrastructure.OutboxKafkaConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxRelayService {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final TradingRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int batchSize;
    private final long claimTimeoutMs;
    private final long sendTimeoutMs;
    private final long initialRetryDelayMs;
    private final long maxRetryDelayMs;
    private final String relayOwner;

    @Autowired
    public OutboxRelayService(TradingRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${app.outbox.relay.enabled:false}") boolean enabled,
                              @Value("${app.outbox.relay.batch-size:50}") int batchSize,
                              @Value("${app.outbox.relay.claim-timeout-ms:30000}") long claimTimeoutMs,
                              @Value("${app.outbox.relay.send-timeout-ms:5000}") long sendTimeoutMs,
                              @Value("${app.outbox.relay.retry-initial-delay-ms:1000}") long initialRetryDelayMs,
                              @Value("${app.outbox.relay.retry-max-delay-ms:60000}") long maxRetryDelayMs) {
        this(repository, kafkaTemplate, objectMapper, enabled, batchSize, claimTimeoutMs, sendTimeoutMs,
                initialRetryDelayMs, maxRetryDelayMs, UUID.randomUUID().toString());
    }

    OutboxRelayService(TradingRepository repository, KafkaTemplate<String, String> kafkaTemplate,
                       ObjectMapper objectMapper, boolean enabled, int batchSize, long claimTimeoutMs,
                       long sendTimeoutMs, long initialRetryDelayMs, long maxRetryDelayMs, String relayOwner) {
        if (batchSize <= 0 || sendTimeoutMs <= 0 || claimTimeoutMs <= sendTimeoutMs) {
            throw new IllegalArgumentException("Outbox batch/send/claim timeout configuration is invalid");
        }
        if (initialRetryDelayMs <= 0 || maxRetryDelayMs < initialRetryDelayMs) {
            throw new IllegalArgumentException("Outbox retry delay configuration is invalid");
        }
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.claimTimeoutMs = claimTimeoutMs;
        this.sendTimeoutMs = sendTimeoutMs;
        this.initialRetryDelayMs = initialRetryDelayMs;
        this.maxRetryDelayMs = maxRetryDelayMs;
        this.relayOwner = relayOwner;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.delay-ms:1000}")
    public void scheduledRelay() {
        if (enabled) {
            relayOnce();
        }
    }

    public int relayOnce() {
        int sent = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.findOutboxTenantIds(now)) {
            for (OutboxEvent candidate : repository.findPendingOutbox(tenantId, now, batchSize)) {
                if (relayOne(candidate)) {
                    sent++;
                }
                if (Thread.currentThread().isInterrupted()) {
                    return sent;
                }
            }
        }
        return sent;
    }

    private boolean relayOne(OutboxEvent candidate) {
        Instant claimTime = Instant.now();
        int claimed = repository.claimOutboxEvent(candidate.tenantId(), candidate.id(), candidate.version(),
                relayOwner, claimTime, claimTime.plusMillis(claimTimeoutMs));
        if (claimed != 1) {
            return false;
        }
        OutboxEvent event = repository.findOutboxById(candidate.tenantId(), candidate.id()).orElse(null);
        if (event == null || !ownsClaim(candidate, event)) {
            return false;
        }
        try {
            String topic = switch (event.aggregateType()) {
                case "entitlement" -> OutboxKafkaConfiguration.ENTITLEMENT_EVENTS_TOPIC;
                case "refund" -> OutboxKafkaConfiguration.REFUND_EVENTS_TOPIC;
                case "settlement", "settlement_batch", "settlement_payment" -> OutboxKafkaConfiguration.SETTLEMENT_EVENTS_TOPIC;
                case "appointment" -> OutboxKafkaConfiguration.APPOINTMENT_EVENTS_TOPIC;
                case "institution", "institution_file" -> OutboxKafkaConfiguration.INSTITUTION_EVENTS_TOPIC;
                case "youzan", "youzan_order", "youzan_sync_issue" -> OutboxKafkaConfiguration.YOUZAN_EVENTS_TOPIC;
                case "wecom" -> OutboxKafkaConfiguration.WECOM_EVENTS_TOPIC;
                case "knowledge_document" -> OutboxKafkaConfiguration.KNOWLEDGE_SYNC_TOPIC;
                case "redemption", "redemption_reversal" -> OutboxKafkaConfiguration.REDEMPTION_EVENTS_TOPIC;
                default -> OutboxKafkaConfiguration.ORDER_EVENTS_TOPIC;
            };
            kafkaTemplate.send(topic, event.partitionKey(), envelope(event))
                    .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            int marked = repository.markOutboxSent(event.tenantId(), event.id(), event.version(), relayOwner, Instant.now());
            if (marked != 1) {
                logClaimLost(event, "SENT");
                return false;
            }
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            int retryCount = event.retryCount() + 1;
            Instant nextRetryAt = Instant.now().plusMillis(retryDelayMillis(retryCount));
            int marked = repository.markOutboxFailed(event.tenantId(), event.id(), event.version(), relayOwner,
                    failureSummary(ex), nextRetryAt);
            if (marked != 1) {
                logClaimLost(event, "FAILED");
            }
            return false;
        }
    }

    private boolean ownsClaim(OutboxEvent candidate, OutboxEvent event) {
        return "CLAIMED".equals(event.status()) && relayOwner.equals(event.claimOwner())
                && event.version().equals(candidate.version() + 1);
    }

    private String envelope(OutboxEvent event) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("event_id", event.eventId());
        envelope.put("event_type", event.eventType());
        envelope.put("event_version", event.eventVersion());
        envelope.put("tenant_id", event.tenantId());
        envelope.put("aggregate_type", event.aggregateType());
        envelope.put("aggregate_id", event.aggregateId());
        envelope.put("occurred_at", event.createdAt().toString());
        envelope.put("correlation_id", event.eventId());
        envelope.set("payload", objectMapper.readTree(event.payloadJson()));
        return objectMapper.writeValueAsString(envelope);
    }

    long retryDelayMillis(int retryCount) {
        long cappedDelay = initialRetryDelayMs;
        for (int i = 1; i < retryCount && cappedDelay < maxRetryDelayMs; i++) {
            cappedDelay = cappedDelay > maxRetryDelayMs / 2
                    ? maxRetryDelayMs : Math.min(maxRetryDelayMs, cappedDelay * 2);
        }
        long minimumDelay = cappedDelay - cappedDelay / 2;
        return minimumDelay + ThreadLocalRandom.current().nextLong(cappedDelay - minimumDelay + 1);
    }

    private String failureSummary(Exception ex) {
        Throwable root = ex;
        for (int i = 0; i < 8 && root.getCause() != null; i++) {
            root = root.getCause();
        }
        String outer = ex.getClass().getSimpleName();
        String cause = root.getClass().getSimpleName();
        return outer.equals(cause) ? outer : outer + " caused by " + cause;
    }

    private void logClaimLost(OutboxEvent event, String targetStatus) {
        log.warn("Outbox claim changed before completion, tenantId={}, outboxId={}, targetStatus={}",
                event.tenantId(), event.id(), targetStatus);
    }
}
