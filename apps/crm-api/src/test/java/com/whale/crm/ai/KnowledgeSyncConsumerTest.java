package com.whale.crm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.ai.application.KnowledgeSyncConsumer;
import com.whale.crm.ai.domain.KnowledgeDocument;
import com.whale.crm.ai.domain.KnowledgeRepository;
import com.whale.crm.ai.domain.KnowledgeSyncJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSyncConsumerTest {
    private KnowledgeRepository repository;
    private JdbcTemplate jdbcTemplate;
    private KnowledgeSyncConsumer consumer;
    private Acknowledgment acknowledgment;

    @BeforeEach
    void setUp() {
        repository = mock(KnowledgeRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        acknowledgment = mock(Acknowledgment.class);
        consumer = new KnowledgeSyncConsumer(repository, jdbcTemplate, new ObjectMapper(),
                mock(PlatformTransactionManager.class));
    }

    @Test
    void validEventInsertsInboxMovesWaitingToPendingAndAcknowledges() {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("crm-knowledge-sync"), eq("event-1"))).thenReturn(1);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("WAITING", 101L)));
        when(repository.markPending(1L, 501L, 0L)).thenReturn(1);

        consumer.listen(message("event-1", 501L, 101L), acknowledgment);

        verify(repository).markPending(1L, 501L, 0L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void duplicateInboxDoesNotMoveJobAgainButAcknowledges() {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("crm-knowledge-sync"), eq("event-1")))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("PENDING", 101L)));

        consumer.listen(message("event-1", 501L, 101L), acknowledgment);

        verify(repository, never()).markPending(1L, 501L, 0L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void disabledDocumentConsumesLateEventWithoutRequeueingJob() {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("crm-knowledge-sync"), eq("event-1"))).thenReturn(1);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("WAITING", 101L)));
        KnowledgeDocument disabled = new KnowledgeDocument(101L, 1L, 10L, null, "dataset-1", null,
                "DISABLED", 1L, null, Instant.now(), Instant.now(), 1L);
        when(repository.findDocumentById(1L, 101L)).thenReturn(Optional.of(disabled));

        consumer.listen(message("event-1", 501L, 101L), acknowledgment);

        verify(repository, never()).markPending(1L, 501L, 0L);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void mismatchedDocumentIsRejectedWithoutAcknowledgment() {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("crm-knowledge-sync"), eq("event-1"))).thenReturn(1);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("WAITING", 999L)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> consumer.listen(message("event-1", 501L, 101L), acknowledgment))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentId");
        verify(acknowledgment, never()).acknowledge();
        verify(repository, never()).markPending(1L, 501L, 0L);
    }

    @Test
    void missingOccurredAtIsRejectedBeforeInboxAndAcknowledgment() {
        String invalid = message("event-1", 501L, 101L).replace("\"occurred_at\":\"2026-10-28T00:00:00Z\",", "");
        assertInvalidContract(invalid);
    }

    @Test
    void payloadCannotCarryTextOrOtherUndeclaredFields() {
        String invalid = message("event-1", 501L, 101L)
                .replace("\"documentId\":101", "\"documentId\":101,\"text\":\"forbidden\"");
        assertInvalidContract(invalid);
    }

    private void assertInvalidContract(String message) {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("crm-knowledge-sync"), eq("event-1"))).thenReturn(1);
        when(repository.findSyncJobById(1L, 501L)).thenReturn(Optional.of(job("WAITING", 101L)));
        when(repository.markPending(1L, 501L, 0L)).thenReturn(1);

        assertThatThrownBy(() -> consumer.listen(message, acknowledgment)).isInstanceOf(IllegalArgumentException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    private String message(String eventId, long jobId, long documentId) {
        return """
                {
                  "event_id":"%s",
                  "event_type":"knowledge.sync.requested.v1",
                  "event_version":1,
                  "tenant_id":1,
                  "aggregate_type":"knowledge_document",
                  "aggregate_id":"%d",
                  "occurred_at":"2026-10-28T00:00:00Z",
                  "correlation_id":"%s",
                  "payload":{"jobId":%d,"documentId":%d}
                }
                """.formatted(eventId, jobId, eventId, jobId, documentId);
    }

    private KnowledgeSyncJob job(String status, long documentId) {
        return new KnowledgeSyncJob(501L, 1L, documentId, 20L, "a".repeat(64), status, 0, 5, null,
                null, null, null, null, null, 1L, "publish-1", "hash", null, null,
                Instant.now(), Instant.now(), 0L);
    }
}
