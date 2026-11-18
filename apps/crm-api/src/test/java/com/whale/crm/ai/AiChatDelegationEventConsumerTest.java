package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.AiChatDelegationEventConsumer;
import com.whale.crm.ai.application.AiChatDelegationService;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiChatDelegationEventConsumerTest {
    @Mock AiChatDelegationService service;
    @Mock WecomRepository wecomRepository;
    @Mock JdbcTemplate jdbcTemplate;

    @Test
    void realOutboxLongEventIdPassesAndInboxDeduplicatesWithoutTruncation() throws Exception {
        String msgId = "m".repeat(140);
        String eventId = "wecom.message.archived.v1:corp:" + msgId;
        assertThat(eventId.length()).isGreaterThan(64).isLessThanOrEqualTo(192);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AiChatDelegationEventConsumer consumer = new AiChatDelegationEventConsumer(service, wecomRepository,
                jdbcTemplate, mapper, new TestTransactionManager());
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        WecomMessage source = new WecomMessage(91L, "corp", msgId, 1L, 101L, "external", 8L,
                "conversation", "TEXT", "CUSTOMER", now, "object", "hash", "GRANTED", "evidence",
                "STORED", "GRANTED", 0L);
        when(wecomRepository.findMessageById(1L, 91L)).thenReturn(Optional.of(source));
        when(jdbcTemplate.update(anyString(), eq(1L), eq(AiChatDelegationEventConsumer.GROUP), eq(eventId)))
                .thenReturn(1).thenThrow(new DuplicateKeyException("duplicate"));

        String envelope = outboxEnvelope(mapper, eventId, msgId, now);
        consumer.consume(envelope);
        consumer.consume(envelope);

        verify(service).receive(1L, 91L);
        verify(jdbcTemplate, times(2)).update(anyString(), eq(1L),
                eq(AiChatDelegationEventConsumer.GROUP), eq(eventId));
    }

    private String outboxEnvelope(ObjectMapper mapper, String eventId, String msgId, Instant now) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageRef", 91);
        payload.put("corpId", "corp");
        payload.put("msgId", msgId);
        payload.put("archiveSeq", 1);
        payload.put("conversationId", "conversation");
        payload.put("customerId", 101);
        payload.put("contentObjectKey", "object");
        payload.put("sentAt", now.toString());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId);
        envelope.put("event_type", "wecom.message.archived.v1");
        envelope.put("event_version", 1);
        envelope.put("tenant_id", 1);
        envelope.put("aggregate_type", "wecom");
        envelope.put("aggregate_id", "91");
        envelope.put("occurred_at", now.toString());
        envelope.put("correlation_id", eventId);
        envelope.put("payload", payload);
        return mapper.writeValueAsString(envelope);
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
