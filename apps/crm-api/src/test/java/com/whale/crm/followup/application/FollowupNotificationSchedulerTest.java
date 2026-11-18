package com.whale.crm.followup.application;

import com.whale.crm.followup.domain.DueFollowupTask;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupScheduleJob;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowupNotificationSchedulerTest {
    @Test
    void dueTaskUsesSharedNotificationWithStableDedupeAndCompletesLease() {
        FollowupAutomationRepository repository = mock(FollowupAutomationRepository.class);
        NotificationRequestService notifications = mock(NotificationRequestService.class);
        Instant dueAt = Instant.parse("2026-11-11T01:00:00Z");
        DueFollowupTask task = new DueFollowupTask(8, 1, 3, 2, dueAt);
        FollowupScheduleJob job = new FollowupScheduleJob(9L, 1, "TASK_DUE_NOTIFICATION", "8:" + dueAt,
                "READY", 0, 0);
        when(repository.findDueTaskTenantIds(any())).thenReturn(List.of(1L));
        when(repository.findDueTasks(eq(1L), any(), eq(20))).thenReturn(List.of(task));
        when(repository.findScheduleJob(1L, "TASK_DUE_NOTIFICATION", "8:" + dueAt)).thenReturn(Optional.of(job));
        when(repository.claimScheduleJob(eq(1L), eq(9L), eq(0L), anyString(), any(), any())).thenReturn(1);
        when(repository.finishScheduleJob(eq(1L), eq(9L), eq(1L), anyString(), eq("DONE"), any(), any(), eq(0)))
                .thenReturn(1);
        FollowupNotificationScheduler scheduler = new FollowupNotificationScheduler(
                repository, notifications, true, 20, 1000, 100, 1000);

        assertThat(scheduler.runOnce()).isEqualTo(1);

        ArgumentCaptor<NotificationRequest> request = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notifications).request(request.capture());
        assertThat(request.getValue().channels()).containsExactlyInAnyOrder(
                NotificationChannel.IN_APP, NotificationChannel.WECOM_APP);
        assertThat(request.getValue().dedupeKey()).isEqualTo("followup.task.due:8:" + dueAt);
        assertThat(request.getValue().contentMasked()).doesNotContain("客户");
        verify(repository).finishScheduleJob(eq(1L), eq(9L), eq(1L), anyString(), eq("DONE"),
                any(), any(), eq(0));
    }

    @Test
    void notificationFailureReleasesLeaseForRetry() {
        FollowupAutomationRepository repository = mock(FollowupAutomationRepository.class);
        NotificationRequestService notifications = mock(NotificationRequestService.class);
        Instant dueAt = Instant.parse("2026-11-11T01:00:00Z");
        DueFollowupTask task = new DueFollowupTask(8, 1, 3, 2, dueAt);
        FollowupScheduleJob job = new FollowupScheduleJob(9L, 1, "TASK_DUE_NOTIFICATION", "8:" + dueAt,
                "READY", 0, 0);
        when(repository.findDueTaskTenantIds(any())).thenReturn(List.of(1L));
        when(repository.findDueTasks(eq(1L), any(), eq(20))).thenReturn(List.of(task));
        when(repository.findScheduleJob(1L, "TASK_DUE_NOTIFICATION", "8:" + dueAt)).thenReturn(Optional.of(job));
        when(repository.claimScheduleJob(eq(1L), eq(9L), eq(0L), anyString(), any(), any())).thenReturn(1);
        when(notifications.request(any())).thenThrow(new IllegalStateException("模拟通知失败"));
        FollowupNotificationScheduler scheduler = new FollowupNotificationScheduler(
                repository, notifications, true, 20, 1000, 100, 1000);

        assertThat(scheduler.runOnce()).isZero();

        verify(repository).finishScheduleJob(eq(1L), eq(9L), eq(1L), anyString(), eq("FAILED"),
                any(), any(), eq(1));
    }
}
