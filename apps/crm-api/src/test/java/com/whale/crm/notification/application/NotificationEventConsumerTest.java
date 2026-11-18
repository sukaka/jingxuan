package com.whale.crm.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventConsumerTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void validRequestActivatesOnlyMatchingNotificationDeliveries() {
        NotificationEventConsumer consumer = consumer();
        Notification notification = new Notification(42L, 7L, 22L, "FOLLOWUP", "标题", "脱敏内容",
                "FOLLOWUP", "9", "followup:9", "/followups/9", "IN_APP,WECOM_APP",
                Instant.parse("2026-01-01T00:00:00Z"), null, 0L);
        when(repository.findById(7L, 42L)).thenReturn(Optional.of(notification));

        consumer.consume(envelope("{\"notificationId\":42}"));

        verify(jdbcTemplate).update(anyString(), eq(7L), eq("crm-notification-request"), eq("event-42"));
        verify(repository).activateDeliveries(7L, 42L);
    }

    @Test
    void payloadWithAnyExtraFieldIsRejectedBeforeInbox() {
        NotificationEventConsumer consumer = consumer();

        assertThatThrownBy(() -> consumer.consume(envelope("{\"notificationId\":42,\"content\":\"forbidden\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
        verify(repository, never()).activateDeliveries(any(Long.class), any(Long.class));
    }

    @Test
    void aggregateMismatchIsRejected() {
        NotificationEventConsumer consumer = consumer();
        String message = envelope("{\"notificationId\":41}");

        assertThatThrownBy(() -> consumer.consume(message)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).activateDeliveries(any(Long.class), any(Long.class));
    }

    private NotificationEventConsumer consumer() {
        return new NotificationEventConsumer(repository, jdbcTemplate, objectMapper,
                new TransactionTemplate(new TestTransactionManager()));
    }

    private String envelope(String payload) {
        return """
                {"event_id":"event-42","event_type":"notification.requested.v1","event_version":1,
                "tenant_id":7,"aggregate_type":"notification","aggregate_id":"42",
                "occurred_at":"2026-01-01T00:00:00Z","correlation_id":"event-42","payload":%s}
                """.formatted(payload);
    }
}
