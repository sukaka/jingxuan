package com.whale.crm.followup.application;

import com.whale.crm.followup.domain.DueFollowupTask;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupScheduleJob;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.web.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class FollowupNotificationScheduler {
    private static final String JOB_TYPE = "TASK_DUE_NOTIFICATION";

    private final FollowupAutomationRepository repository;
    private final NotificationRequestService notificationService;
    private final boolean enabled;
    private final int batchSize;
    private final long leaseMs;
    private final long retryInitialDelayMs;
    private final long retryMaxDelayMs;
    private final String owner = UUID.randomUUID().toString();

    public FollowupNotificationScheduler(
            FollowupAutomationRepository repository, NotificationRequestService notificationService,
            @Value("${app.followup.automation.scheduler-enabled:true}") boolean enabled,
            @Value("${app.followup.automation.batch-size:20}") int batchSize,
            @Value("${app.followup.automation.notification-lease-ms:30000}") long leaseMs,
            @Value("${app.followup.automation.retry-initial-delay-ms:5000}") long retryInitialDelayMs,
            @Value("${app.followup.automation.retry-max-delay-ms:300000}") long retryMaxDelayMs) {
        this.repository = repository;
        this.notificationService = notificationService;
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.leaseMs = leaseMs;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryMaxDelayMs = retryMaxDelayMs;
    }

    @Scheduled(fixedDelayString = "${app.followup.automation.notification-delay-ms:30000}",
            initialDelayString = "${app.followup.automation.initial-delay-ms:30000}")
    public void scheduled() {
        if (enabled) {
            runOnce();
        }
    }

    public int runOnce() {
        int completed = 0;
        Instant now = Instant.now();
        for (Long tenantId : repository.findDueTaskTenantIds(now)) {
            for (DueFollowupTask task : repository.findDueTasks(tenantId, now, batchSize)) {
                if (notify(task, now)) {
                    completed++;
                }
            }
        }
        return completed;
    }

    private boolean notify(DueFollowupTask task, Instant now) {
        String jobKey = task.id() + ":" + task.dueAt();
        repository.insertScheduleJobIgnore(task.tenantId(), JOB_TYPE, jobKey);
        FollowupScheduleJob job = repository.findScheduleJob(task.tenantId(), JOB_TYPE, jobKey).orElseThrow();
        Instant claimedAt = Instant.now();
        if (repository.claimScheduleJob(task.tenantId(), job.id(), job.version(), owner,
                claimedAt, claimedAt.plusMillis(leaseMs)) != 1) {
            return false;
        }
        try {
            notificationService.request(new NotificationRequest(task.tenantId(), task.assigneeUserId(),
                    "FOLLOWUP_DUE", "回访任务到期", "你有一条回访任务已到期，请及时处理",
                    "TASK", Long.toString(task.id()), "followup.task.due:" + jobKey,
                    "/app/tasks?taskId=" + task.id(),
                    Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP)));
            return repository.finishScheduleJob(task.tenantId(), job.id(), job.version() + 1, owner,
                    "DONE", null, null, 0) == 1;
        } catch (RuntimeException ex) {
            int retryCount = job.retryCount() + 1;
            repository.finishScheduleJob(task.tenantId(), job.id(), job.version() + 1, owner,
                    "FAILED", masked(ex), Instant.now().plusMillis(retryDelayMillis(retryCount)), 1);
            return false;
        }
    }

    private long retryDelayMillis(int retryCount) {
        long delay = retryInitialDelayMs;
        for (int index = 1; index < retryCount && delay < retryMaxDelayMs; index++) {
            delay = delay > retryMaxDelayMs / 2 ? retryMaxDelayMs : Math.min(retryMaxDelayMs, delay * 2);
        }
        return delay;
    }

    private String masked(RuntimeException error) {
        if (error instanceof BusinessException business) {
            String message = business.getMessage();
            return message.length() <= 400 ? message : message.substring(0, 400);
        }
        return "到期通知请求失败（" + error.getClass().getSimpleName() + "）";
    }
}
