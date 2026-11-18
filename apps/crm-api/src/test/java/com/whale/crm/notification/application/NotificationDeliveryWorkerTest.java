package com.whale.crm.notification.application;

import com.whale.crm.notification.domain.Notification;
import com.whale.crm.notification.domain.NotificationAttempt;
import com.whale.crm.notification.domain.NotificationDelivery;
import com.whale.crm.notification.domain.NotificationRepository;
import com.whale.crm.notification.domain.WecomSendResult;
import com.whale.crm.notification.infrastructure.wecom.WecomMemberMappingResolver;
import com.whale.crm.notification.infrastructure.wecom.WecomMessageClientRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryWorkerTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final WecomMessageClientRouter client = mock(WecomMessageClientRouter.class);
    private final WecomMemberMappingResolver mappings = mock(WecomMemberMappingResolver.class);
    private NotificationDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new NotificationDeliveryWorker(repository, client, mappings,
                new TransactionTemplate(new TestTransactionManager()), properties(), "worker-test");
        when(repository.findExpiredDeliveries(any(), anyInt())).thenReturn(List.of());
        when(repository.findDeliveryTenantIds(any())).thenReturn(List.of(7L));
        when(mappings.resolve(7L, 22L)).thenReturn("staff-22");
        when(repository.findById(7L, 42L)).thenReturn(Optional.of(notification()));
    }

    @Test
    void unknownOutcomeIsTerminalAndRecordedWithoutRetryTime() {
        NotificationDelivery candidate = delivery("PENDING", 3L);
        NotificationDelivery claimed = delivery("SENDING", 4L);
        stubClaim(candidate, claimed);
        when(client.send(any())).thenReturn(WecomSendResult.unknown("OFFICIAL", "WECOM_RESPONSE_UNKNOWN"));
        when(repository.completeDelivery(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), any())).thenReturn(1);

        assertThat(worker.runOnce()).isEqualTo(1);

        verify(repository).completeDelivery(eq(7L), eq(9L), eq(4L), eq("worker-test"), eq("UNKNOWN"),
                eq("OFFICIAL"), isNull(), eq("WECOM_RESPONSE_UNKNOWN"), isNull(), eq(1), any());
        ArgumentCaptor<NotificationAttempt> attempt = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(repository).insertAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo("UNKNOWN");
        assertThat(attempt.getValue().attemptNo()).isEqualTo(3);
        assertThat(attempt.getValue().triggerType()).isEqualTo("AUTOMATIC");
    }

    @Test
    void retryableOutcomeUsesBoundedBackoffAndAppendsAttempt() {
        NotificationDelivery candidate = delivery("RETRY_WAIT", 3L);
        NotificationDelivery claimed = delivery("SENDING", 4L);
        stubClaim(candidate, claimed);
        when(client.send(any())).thenReturn(new WecomSendResult(WecomSendResult.Status.RETRYABLE,
                "OFFICIAL", null, "WECOM_RATE_LIMIT_45009"));
        when(repository.completeDelivery(anyLong(), anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), any())).thenReturn(1);

        assertThat(worker.runOnce()).isEqualTo(1);

        verify(repository).completeDelivery(eq(7L), eq(9L), eq(4L), eq("worker-test"), eq("RETRY_WAIT"),
                eq("OFFICIAL"), isNull(), eq("WECOM_RATE_LIMIT_45009"), any(), eq(2), any());
        ArgumentCaptor<NotificationAttempt> attempt = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(repository).insertAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo("RETRYABLE");
        for (int retry = 1; retry <= 100; retry++) {
            assertThat(worker.retryDelayMillis(retry)).isBetween(50L, 400L);
        }
    }

    @Test
    void expiredLeaseBecomesUnknownWithoutCallingExternalClient() {
        NotificationDelivery expired = delivery("SENDING", 4L);
        when(repository.findExpiredDeliveries(any(), anyInt())).thenReturn(List.of(expired));
        when(repository.findDeliveryTenantIds(any())).thenReturn(List.of());
        when(repository.recoverExpiredDelivery(anyLong(), anyLong(), anyLong(), any(), anyString(), any()))
                .thenReturn(1);

        assertThat(worker.runOnce()).isZero();

        verify(repository).recoverExpiredDelivery(eq(7L), eq(9L), eq(4L), eq("worker-test"),
                eq("WORKER_LEASE_EXPIRED_UNKNOWN"), any());
        ArgumentCaptor<NotificationAttempt> attempt = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(repository).insertAttempt(attempt.capture());
        assertThat(attempt.getValue().status()).isEqualTo("UNKNOWN");
        assertThat(attempt.getValue().triggerType()).isEqualTo("LEASE_RECOVERY");
        verify(client, never()).send(any());
    }

    private void stubClaim(NotificationDelivery candidate, NotificationDelivery claimed) {
        when(repository.findClaimableDeliveries(eq(7L), any(), eq(10))).thenReturn(List.of(candidate));
        when(repository.claimDelivery(eq(7L), eq(9L), eq(3L), eq("worker-test"), any(), any())).thenReturn(1);
        when(repository.findDeliveryById(7L, 9L)).thenReturn(Optional.of(claimed));
    }

    private NotificationDeliveryProperties properties() {
        return new NotificationDeliveryProperties(false, 10, 30000, 5, 100, 400);
    }

    private Notification notification() {
        return new Notification(42L, 7L, 22L, "FOLLOWUP", "标题", "脱敏正文", "FOLLOWUP", "8",
                "followup:8", "/followups/8", "IN_APP,WECOM_APP", Instant.parse("2026-01-01T00:00:00Z"),
                null, 0L);
    }

    private NotificationDelivery delivery(String status, long version) {
        boolean sending = "SENDING".equals(status);
        return new NotificationDelivery(9L, 7L, 42L, 22L, "WECOM_APP", status, sending ? 3 : 2, 1,
                Instant.parse("2026-01-01T00:00:00Z"), sending ? "worker-test" : null,
                sending ? Instant.now().plusSeconds(30) : null, Instant.now(), "AUTOMATIC", null,
                null, null, null, Instant.now(), Instant.now(), version);
    }
}
