package com.whale.crm.notification.application;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.trading.application.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRequestServiceTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private NotificationRequestService service;

    @BeforeEach
    void setUp() {
        service = new NotificationRequestService(repository, outboxService);
    }

    @Test
    void persistsDeliveriesAndMinimalOutboxPayloadInOneRequest() {
        NotificationRequest request = request("followup-due:1");
        Notification stored = notification(42L, request);
        when(repository.recipientActive(7L, 22L)).thenReturn(true);
        when(repository.findByDedupeKey(7L, 22L, request.dedupeKey()))
                .thenReturn(Optional.empty(), Optional.of(stored));

        Notification result = service.request(request);

        assertThat(result.id()).isEqualTo(42L);
        verify(repository).insertNotification(any(Notification.class));
        verify(repository).insertDelivery(eq(7L), eq(42L), eq(22L), eq(NotificationChannel.IN_APP), any(Instant.class));
        verify(repository).insertDelivery(eq(7L), eq(42L), eq(22L), eq(NotificationChannel.WECOM_APP), any(Instant.class));
        verify(outboxService).append(eq(7L), eq("notification.requested.v1:42"),
                eq("notification.requested.v1"), eq("notification"), eq("42"), eq(Map.of("notificationId", 42L)));
    }

    @Test
    void sameDedupeKeyAndSameLogicalObjectDoesNotWriteAgain() {
        NotificationRequest request = request("complaint:9");
        Notification stored = notification(43L, request);
        when(repository.findByDedupeKey(7L, 22L, request.dedupeKey())).thenReturn(Optional.of(stored));

        assertThat(service.request(request)).isEqualTo(stored);

        verify(repository, never()).insertNotification(any());
        verify(repository, never()).insertDelivery(anyLong(), anyLong(), anyLong(), any(), any());
        verify(outboxService, never()).append(anyLong(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sameDedupeKeyWithDifferentContentIsRejected() {
        NotificationRequest request = request("complaint:10");
        Notification stored = notification(44L, request);
        when(repository.findByDedupeKey(7L, 22L, request.dedupeKey())).thenReturn(Optional.of(stored));
        NotificationRequest changed = new NotificationRequest(7L, 22L, "COMPLAINT", "不同标题", "已脱敏内容",
                "COMPLAINT", "10", "complaint:10", "/complaints/10",
                Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP));

        assertThatThrownBy(() -> service.request(changed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("去重键");
    }

    private NotificationRequest request(String dedupeKey) {
        return new NotificationRequest(7L, 22L, "COMPLAINT", "客诉待处理", "客户已脱敏",
                "COMPLAINT", "10", dedupeKey, "/complaints/10",
                Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP));
    }

    private Notification notification(long id, NotificationRequest request) {
        return new Notification(id, request.tenantId(), request.recipientUserId(), request.notificationType(),
                request.title(), request.contentMasked(), request.sourceType(), request.sourceId(), request.dedupeKey(),
                request.deepLink(), "IN_APP,WECOM_APP", Instant.parse("2026-01-01T00:00:00Z"), null, 0L);
    }
}
