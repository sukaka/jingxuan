package com.whale.crm.trading.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.trading.domain.OutboxEvent;
import com.whale.crm.trading.domain.TradingRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class OutboxService {
    private final TradingRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(TradingRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public OutboxEvent append(long tenantId, String eventId, String eventType, String aggregateType,
                              String aggregateId, Object payload) {
        return repository.findOutboxByEventId(tenantId, eventId).orElseGet(() -> {
            try {
                OutboxEvent event = new OutboxEvent(null, tenantId, eventId, eventType, 1, aggregateType, aggregateId,
                        aggregateId, objectMapper.writeValueAsString(payload), "NEW", 0, null, null,
                        null, null, null, Instant.now(), 0L);
                repository.insertOutboxEvent(event);
                return repository.findOutboxByEventId(tenantId, eventId).orElseThrow();
            } catch (Exception ex) {
                if (ex instanceof RuntimeException runtime && runtime.getCause() instanceof java.sql.SQLException) {
                    throw runtime;
                }
                throw new IllegalStateException("Outbox 事件序列化失败", ex);
            }
        });
    }
}
