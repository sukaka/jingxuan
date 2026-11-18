package com.whale.crm.followup.application;

import com.whale.crm.customer.application.TaskApplicationService;
import com.whale.crm.customer.api.dto.TaskItemResponse;
import com.whale.crm.followup.domain.FollowupAutomationRepository;
import com.whale.crm.followup.domain.FollowupBatch;
import com.whale.crm.followup.domain.FollowupBatchItem;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MonthlyBatchExecutionService {
    private final FollowupAutomationRepository repository;
    private final TaskApplicationService taskService;
    private final NotificationRequestService notificationService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final TransactionTemplate transactions;
    private final long leaseMs;
    private final long retryInitialDelayMs;
    private final long retryMaxDelayMs;

    public MonthlyBatchExecutionService(
            FollowupAutomationRepository repository, TaskApplicationService taskService,
            NotificationRequestService notificationService, AuditService auditService,
            IdempotencyService idempotencyService, PlatformTransactionManager transactionManager,
            @Value("${app.followup.automation.batch-lease-ms:60000}") long leaseMs,
            @Value("${app.followup.automation.retry-initial-delay-ms:5000}") long retryInitialDelayMs,
            @Value("${app.followup.automation.retry-max-delay-ms:300000}") long retryMaxDelayMs) {
        this.repository = repository;
        this.taskService = taskService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leaseMs = leaseMs;
        this.retryInitialDelayMs = retryInitialDelayMs;
        this.retryMaxDelayMs = retryMaxDelayMs;
    }

    @jakarta.annotation.PostConstruct
    void validateConfiguration() {
        if (leaseMs <= 0 || retryInitialDelayMs <= 0 || retryMaxDelayMs < retryInitialDelayMs) {
            throw new IllegalArgumentException("跟进批次租约与重试配置无效");
        }
    }

    public void run(FollowupBatch candidate, CurrentUser actor, ExecutionIdempotency idempotency) {
        String leaseOwner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Boolean claimed = transactions.execute(status -> repository.claimBatch(candidate.tenantId(), candidate.id(),
                candidate.version(), candidate.status(), leaseOwner, now, now.plusMillis(leaseMs)) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次正在执行或版本已变化，请刷新后重试");
        }
        FollowupBatch running = repository.findBatchById(candidate.tenantId(), candidate.id()).orElseThrow();
        List<String> errors = new ArrayList<>();
        for (FollowupBatchItem item : repository.findItems(candidate.tenantId(), candidate.id())) {
            if (!FollowupBatchItem.PENDING.equals(item.status()) && !FollowupBatchItem.FAILED.equals(item.status())) {
                continue;
            }
            try {
                transactions.execute(status -> {
                    processItem(running, item.id(), actor);
                    return null;
                });
            } catch (RuntimeException ex) {
                String error = masked(ex);
                errors.add(error);
                transactions.execute(status -> {
                    markItemFailed(running.tenantId(), item.id(), error);
                    return null;
                });
            }
        }
        finish(running, actor, leaseOwner, errors, idempotency);
    }

    private void processItem(FollowupBatch batch, long itemId, CurrentUser actor) {
        FollowupBatchItem item = repository.findItemForUpdate(batch.tenantId(), itemId).orElseThrow();
        if (!FollowupBatchItem.PENDING.equals(item.status()) && !FollowupBatchItem.FAILED.equals(item.status())) {
            return;
        }
        List<Long> orderIds = repository.findOrderIds(batch.tenantId(), batch.id(), item.id());
        String sourceRef = CrmTaskSource.MONTHLY_BATCH + ":" + batch.id() + ":" + item.id();
        TaskApplicationService.AutomaticTaskResult result = taskService.createFromMonthlyBatch(
                batch.tenantId(), actor, item.customerId(), batch.orderMonth(), sourceRef, item.dueAt(), orderIds.size());
        TaskItemResponse task = result.task();
        if (result.created() && task.assigneeUserId() != null) {
            notificationService.request(new NotificationRequest(batch.tenantId(), task.assigneeUserId(),
                    "FOLLOWUP_BATCH_ASSIGNED", "月度回访任务已分配", "你有一条三个月回访任务，请及时处理",
                    "FOLLOWUP_BATCH", Long.toString(task.id()), "followup.batch.assigned:" + task.id(),
                    "/app/tasks?taskId=" + task.id(),
                    Set.of(NotificationChannel.IN_APP, NotificationChannel.WECOM_APP)));
        }
        String status = result.created() ? FollowupBatchItem.CREATED : FollowupBatchItem.SKIPPED;
        String reason = result.created() ? null : "同客户已有开放自动任务或批次任务已存在，未重开任务";
        if (repository.updateItemStatus(batch.tenantId(), item.id(), item.version(), status, task.id(), reason) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "批次明细已变化，请重试");
        }
        auditService.record(batch.tenantId(), actor.userId(), "FOLLOWUP_BATCH_ITEM_" + status,
                "FOLLOWUP_BATCH_ITEM", Long.toString(item.id()), itemSnapshot(item),
                Map.of("status", status, "taskId", task.id(), "reason", reason == null ? "" : reason), null);
    }

    private void markItemFailed(long tenantId, long itemId, String error) {
        FollowupBatchItem item = repository.findItemForUpdate(tenantId, itemId).orElseThrow();
        if (!FollowupBatchItem.PENDING.equals(item.status()) && !FollowupBatchItem.FAILED.equals(item.status())) {
            return;
        }
        repository.updateItemStatus(tenantId, item.id(), item.version(), FollowupBatchItem.FAILED, null, error);
    }

    private void finish(FollowupBatch running, CurrentUser actor, String leaseOwner, List<String> errors,
                        ExecutionIdempotency idempotency) {
        boolean failed = !errors.isEmpty();
        String status = failed ? FollowupBatch.FAILED : FollowupBatch.COMPLETED;
        String error = failed ? errors.getFirst() : null;
        Instant finishedAt = Instant.now();
        Instant nextRetryAt = failed ? finishedAt.plusMillis(retryDelayMillis(running.retryCount() + 1)) : null;
        transactions.executeWithoutResult(transaction -> {
            int updated = repository.finishBatch(running.tenantId(), running.id(), running.version(), leaseOwner,
                    status, error, nextRetryAt, finishedAt, failed ? 1 : 0);
            if (updated != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "批次租约已失效，停止写入完成状态");
            }
            repository.insertAttempt(running.tenantId(), running.id(), running.retryCount() + 1,
                    status, actor.userId(), error);
            FollowupBatch after = repository.findBatchById(running.tenantId(), running.id()).orElseThrow();
            auditService.record(running.tenantId(), actor.userId(), "FOLLOWUP_BATCH_" + status,
                    "FOLLOWUP_BATCH", Long.toString(running.id()), batchSnapshot(running), batchSnapshot(after),
                    failed ? "存在失败明细，可按版本重试" : null);
            if (idempotency != null) {
                idempotencyService.recordSuccess(running.tenantId(), idempotency.scope(), idempotency.key(),
                        "FOLLOWUP_BATCH", Long.toString(running.id()), idempotency.payload());
            }
        });
    }

    long retryDelayMillis(int retryCount) {
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
        return "批次明细执行失败（" + error.getClass().getSimpleName() + "）";
    }

    private Map<String, Object> itemSnapshot(FollowupBatchItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("customerId", item.customerId());
        value.put("status", item.status());
        value.put("taskId", item.taskId());
        value.put("version", item.version());
        return value;
    }

    private Map<String, Object> batchSnapshot(FollowupBatch batch) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", batch.status());
        value.put("retryCount", batch.retryCount());
        value.put("nextRetryAt", batch.nextRetryAt());
        value.put("version", batch.version());
        return value;
    }

    public record ExecutionIdempotency(String scope, String key, String payload) {
    }

    private static final class CrmTaskSource {
        private static final String MONTHLY_BATCH = "MONTHLY_BATCH";
    }
}
