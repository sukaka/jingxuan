package com.whale.crm.customer.batchtag.application;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.CustomerTagSyncPort;
import com.whale.crm.customer.batchtag.domain.BatchTagJob;
import com.whale.crm.customer.batchtag.domain.BatchTagJobAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobCounts;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItem;
import com.whale.crm.customer.batchtag.domain.BatchTagJobItemAttempt;
import com.whale.crm.customer.batchtag.domain.BatchTagJobRepository;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.notification.domain.NotificationChannel;
import com.whale.crm.notification.domain.NotificationRequest;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.CurrentUserService;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BatchTagJobExecutionService {
    private static final Logger log = LoggerFactory.getLogger(BatchTagJobExecutionService.class);

    private final BatchTagJobRepository repository;
    private final CustomerRepository customerRepository;
    private final CustomerTagRepository tagRepository;
    private final CustomerScopeSupport scopeSupport;
    private final CurrentUserService currentUserService;
    private final DictionaryApplicationService dictionaryService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final NotificationRequestService notificationService;
    private final BatchTagJson json;
    private final TransactionTemplate transactions;
    private final long leaseMs;
    private final ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider;

    public BatchTagJobExecutionService(BatchTagJobRepository repository, CustomerRepository customerRepository,
            CustomerTagRepository tagRepository, CustomerScopeSupport scopeSupport,
            CurrentUserService currentUserService, DictionaryApplicationService dictionaryService,
            AuditService auditService, IdempotencyService idempotencyService,
            NotificationRequestService notificationService, BatchTagJson json,
            PlatformTransactionManager transactionManager,
            @Value("${app.batch-tag.lease-ms:60000}") long leaseMs) {
        this(repository, customerRepository, tagRepository, scopeSupport, currentUserService, dictionaryService,
                auditService, idempotencyService, notificationService, json, transactionManager, leaseMs, null);
    }

    @Autowired
    public BatchTagJobExecutionService(BatchTagJobRepository repository, CustomerRepository customerRepository,
            CustomerTagRepository tagRepository, CustomerScopeSupport scopeSupport,
            CurrentUserService currentUserService, DictionaryApplicationService dictionaryService,
            AuditService auditService, IdempotencyService idempotencyService,
            NotificationRequestService notificationService, BatchTagJson json,
            PlatformTransactionManager transactionManager,
            @Value("${app.batch-tag.lease-ms:60000}") long leaseMs,
            ObjectProvider<CustomerTagSyncPort> tagSyncPortProvider) {
        this.repository = repository;
        this.customerRepository = customerRepository;
        this.tagRepository = tagRepository;
        this.scopeSupport = scopeSupport;
        this.currentUserService = currentUserService;
        this.dictionaryService = dictionaryService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.notificationService = notificationService;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
        this.leaseMs = leaseMs;
        this.tagSyncPortProvider = tagSyncPortProvider;
    }

    @jakarta.annotation.PostConstruct
    void validateConfiguration() {
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("批量重打标租约配置无效");
        }
    }

    public void run(BatchTagJob candidate, ExecutionIdempotency idempotency) {
        String leaseOwner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Boolean claimed = transactions.execute(status -> repository.claimJob(candidate.tenantId(), candidate.id(),
                candidate.version(), candidate.status(), leaseOwner, now, now.plusMillis(leaseMs)) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务正在执行或版本已变化，请刷新后重试");
        }
        BatchTagJob running = repository.findJobById(candidate.tenantId(), candidate.id()).orElseThrow();
        CurrentUser actor = currentUserService.loadById(running.requestedBy())
                .filter(user -> user.tenantId() == running.tenantId()).orElse(null);
        List<String> errors = new ArrayList<>();
        for (BatchTagJobItem item : repository.findWorkItems(running.tenantId(), running.id())) {
            try {
                transactions.executeWithoutResult(status -> processItem(running, item.id(), actor, leaseOwner));
            } catch (LeaseLostException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                String code = ex instanceof ItemFailureException failure ? failure.code : "ITEM_EXECUTION_FAILED";
                String error = masked(ex);
                errors.add(error);
                transactions.executeWithoutResult(status -> markItemFailed(running, item.id(), code, error));
            }
            renewLease(running, leaseOwner);
        }
        finish(running, actor, leaseOwner, errors, idempotency);
    }

    private void processItem(BatchTagJob running, long itemId, CurrentUser actor, String leaseOwner) {
        BatchTagJobItem item = repository.findItemForUpdate(running.tenantId(), itemId).orElseThrow();
        if (!isWork(item)) {
            return;
        }
        if (!hasWritePermission(running.tenantId(), actor)) {
            requireLease(running, leaseOwner);
            completeResult(running, item, BatchTagJobItem.SKIPPED, "PERMISSION_REVOKED",
                    "请求人已停用、转为机构账号或客户写权限/数据范围已撤销", null, null, List.of());
            return;
        }
        Customer customer = customerRepository.findByIdForUpdate(running.tenantId(), item.customerId()).orElse(null);
        requireLease(running, leaseOwner);
        if (customer == null) {
            completeResult(running, item, BatchTagJobItem.SKIPPED, "CUSTOMER_NOT_FOUND",
                    "客户不存在，未进行标签变更", null, null, List.of());
            return;
        }
        if (repository.findConfirmedMergeTarget(running.tenantId(), item.customerId()).isPresent()) {
            completeResult(running, item, BatchTagJobItem.SKIPPED, "MERGE_CONFLICT",
                    "客户已合并到其他客户，未自动改挂或处理新主客户", null, null, List.of());
            return;
        }
        if (customer.isDeleted()) {
            completeResult(running, item, BatchTagJobItem.SKIPPED, "CUSTOMER_NOT_FOUND",
                    "客户已归档，未进行标签变更", null, null, List.of());
            return;
        }
        DataScope scope = scopeSupport.scope(actor);
        if (scopeSupport.isEmptyScope(scope)) {
            completeResult(running, item, BatchTagJobItem.SKIPPED, "PERMISSION_REVOKED",
                    "请求人客户数据范围已撤销", null, null, List.of());
            return;
        }
        if (!scopeSupport.isVisible(scope, actor, customer)) {
            completeResult(running, item, BatchTagJobItem.SKIPPED, "OWNERSHIP_OUT_OF_SCOPE",
                    "客户当前归属已超出请求人实时数据范围", null, null, List.of());
            return;
        }
        List<String> requested = json.stringList(item.tagCodesJson());
        if (BatchTagJob.ADD.equals(running.operation())) {
            requireActiveTags(running.tenantId(), requested);
        }
        List<String> before = tagRepository.findTagCodes(running.tenantId(), item.customerId());
        LinkedHashSet<String> existing = new LinkedHashSet<>(before);
        LinkedHashSet<String> changed = new LinkedHashSet<>();
        for (String tag : requested) {
            if (BatchTagJob.ADD.equals(running.operation())) {
                if (existing.add(tag) && tagRepository.insertIfAbsent(running.tenantId(), item.customerId(), tag,
                        running.requestedBy()) == 1) {
                    changed.add(tag);
                }
            } else if (existing.remove(tag)
                    && tagRepository.delete(running.tenantId(), item.customerId(), tag) == 1) {
                changed.add(tag);
            }
        }
        List<String> after = tagRepository.findTagCodes(running.tenantId(), item.customerId());
        List<String> changedTags = changed.stream().sorted().toList();
        String status = changedTags.isEmpty() ? BatchTagJobItem.NO_CHANGE : BatchTagJobItem.SUCCEEDED;
        String reason = changedTags.isEmpty() ? "目标标签状态已符合，未重复变更" : "按请求增量更新，其他历史标签保持不变";
        completeResult(running, item, status, null, reason, before, after, changedTags);
        if (!changedTags.isEmpty()) {
            notifyTagSync(running.tenantId(), item.customerId(), before, after, running.requestedBy());
        }
    }

    private void notifyTagSync(long tenantId, long customerId, List<String> before, List<String> after,
                               Long actorUserId) {
        if (tagSyncPortProvider == null) {
            return;
        }
        CustomerTagSyncPort port = tagSyncPortProvider.getIfAvailable();
        if (port == null) {
            return;
        }
        try {
            port.onCustomerTagsChanged(tenantId, customerId, before, after, actorUserId, "BATCH_TAG");
        } catch (RuntimeException ex) {
            log.warn("批量打标后企微标签推送入队失败，已忽略以保护批量任务: customerId={}", customerId);
        }
    }

    private void completeResult(BatchTagJob running, BatchTagJobItem item, String resultStatus, String failureCode,
                                String reason, List<String> before, List<String> after, List<String> changed) {
        Instant now = Instant.now();
        String beforeJson = before == null ? null : json.json(before);
        String afterJson = after == null ? null : json.json(after);
        String changedJson = json.json(changed);
        if (repository.updateItemResult(running.tenantId(), item.id(), item.version(), resultStatus, failureCode,
                reason, beforeJson, afterJson, changedJson, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务明细已变化，停止重复处理");
        }
        repository.insertItemAttempt(new BatchTagJobItemAttempt(null, running.tenantId(), running.id(), item.id(),
                item.attemptCount() + 1, resultStatus, failureCode, reason, beforeJson, afterJson, changedJson,
                running.requestedBy(), now));
        Map<String, Object> beforeSnapshot = itemAuditSnapshot(running, item);
        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("jobId", running.id());
        afterSnapshot.put("itemId", item.id());
        afterSnapshot.put("customerId", item.customerId());
        afterSnapshot.put("status", resultStatus);
        afterSnapshot.put("failureCode", failureCode);
        afterSnapshot.put("beforeTagCodes", before);
        afterSnapshot.put("afterTagCodes", after);
        afterSnapshot.put("changedTagCodes", changed);
        afterSnapshot.put("attemptCount", item.attemptCount() + 1);
        auditService.record(running.tenantId(), running.requestedBy(), "BATCH_TAG_ITEM_" + resultStatus,
                "CUSTOMER", Long.toString(item.customerId()), beforeSnapshot, afterSnapshot, reason);
    }

    private void markItemFailed(BatchTagJob running, long itemId, String failureCode, String error) {
        BatchTagJobItem item = repository.findItemForUpdate(running.tenantId(), itemId).orElse(null);
        if (item == null || !isWork(item)) {
            return;
        }
        completeResult(running, item, BatchTagJobItem.FAILED, failureCode, error, null, null, List.of());
    }

    private void finish(BatchTagJob running, CurrentUser actor, String leaseOwner, List<String> errors,
                        ExecutionIdempotency idempotency) {
        transactions.executeWithoutResult(status -> {
            if (repository.findWorkItems(running.tenantId(), running.id()).stream()
                    .anyMatch(item -> BatchTagJobItem.PENDING.equals(item.status()))) {
                throw new BusinessException(ErrorCode.CONFLICT, "仍有待处理明细，不能提前完成任务");
            }
            BatchTagJobCounts counts = repository.countItemResults(running.tenantId(), running.id());
            String finalStatus = finalStatus(running, counts);
            String error = errors.isEmpty() ? null : errors.getFirst();
            Instant finishedAt = Instant.now();
            if (repository.finishJob(running.tenantId(), running.id(), running.version(), leaseOwner, finalStatus,
                    counts, error, finishedAt) != 1) {
                throw new LeaseLostException();
            }
            String trigger = idempotency == null ? "RECOVERY" : idempotency.triggerType();
            repository.insertJobAttempt(new BatchTagJobAttempt(null, running.tenantId(), running.id(),
                    running.attemptCount() + 1, finalStatus, trigger, running.requestedBy(), error, finishedAt));
            BatchTagJob after = repository.findJobById(running.tenantId(), running.id()).orElseThrow();
            auditService.record(running.tenantId(), running.requestedBy(), "BATCH_TAG_JOB_" + finalStatus,
                    "BATCH_TAG_JOB", running.id().toString(), jobSnapshot(running), jobSnapshot(after), error);
            if (idempotency != null) {
                idempotencyService.recordSuccess(running.tenantId(), idempotency.scope(), idempotency.key(),
                        "BATCH_TAG_JOB", running.id().toString(), idempotency.payload());
            }
            if (actor != null) {
                notificationService.request(new NotificationRequest(running.tenantId(), running.requestedBy(),
                        "BATCH_TAG_JOB_FINISHED", "批量重打标任务已完成",
                        "任务 " + running.jobNo() + "：" + statusLabel(finalStatus) + "，成功 " + counts.succeededCount()
                                + "，无需变更 " + counts.noChangeCount() + "，跳过 " + counts.skippedCount()
                                + "，失败 " + counts.failedCount(),
                        "BATCH_TAG_JOB", running.id().toString(),
                        "batch-tag-job:" + running.id() + ":" + running.attemptCount(),
                        "/customers/batch-tag-jobs?jobId=" + running.id(), Set.of(NotificationChannel.IN_APP)));
            }
        });
    }

    private String finalStatus(BatchTagJob running, BatchTagJobCounts counts) {
        if (counts.failedCount() == running.totalCount()) {
            return BatchTagJob.FAILED;
        }
        if (counts.failedCount() > 0 || counts.skippedCount() > 0) {
            return BatchTagJob.PARTIAL_FAILED;
        }
        return BatchTagJob.SUCCEEDED;
    }

    private void requireActiveTags(long tenantId, List<String> tags) {
        try {
            tags.forEach(tag -> dictionaryService.requireActiveOption(tenantId,
                    BatchTagJobApplicationService.TAG_DICTIONARY, tag));
        } catch (BusinessException ex) {
            throw new ItemFailureException("TAG_DICTIONARY_INACTIVE", "标签不存在或已停用，重新启用后可重试失败项");
        }
    }

    private boolean hasWritePermission(long tenantId, CurrentUser actor) {
        return actor != null && actor.tenantId() == tenantId && !actor.isInstitutionUser()
                && actor.permissions().contains("customer:write");
    }

    private boolean isWork(BatchTagJobItem item) {
        return BatchTagJobItem.PENDING.equals(item.status()) || BatchTagJobItem.FAILED.equals(item.status());
    }

    private void requireLease(BatchTagJob running, String leaseOwner) {
        BatchTagJob current = repository.findJobById(running.tenantId(), running.id()).orElse(null);
        if (current == null || current.version() != running.version() || !BatchTagJob.RUNNING.equals(current.status())
                || !leaseOwner.equals(current.leaseOwner())) {
            throw new LeaseLostException();
        }
    }

    private void renewLease(BatchTagJob running, String leaseOwner) {
        Boolean renewed = transactions.execute(status -> repository.renewLease(running.tenantId(), running.id(),
                running.version(), leaseOwner, Instant.now().plusMillis(leaseMs)) == 1);
        if (!Boolean.TRUE.equals(renewed)) {
            throw new LeaseLostException();
        }
    }

    private Map<String, Object> itemAuditSnapshot(BatchTagJob running, BatchTagJobItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", running.id());
        value.put("itemId", item.id());
        value.put("customerId", item.customerId());
        value.put("status", item.status());
        value.put("operation", running.operation());
        value.put("requestedTagCodes", json.stringList(item.tagCodesJson()));
        value.put("attemptCount", item.attemptCount());
        return value;
    }

    private Map<String, Object> jobSnapshot(BatchTagJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobId", job.id());
        value.put("jobNo", job.jobNo());
        value.put("status", job.status());
        value.put("succeededCount", job.succeededCount());
        value.put("noChangeCount", job.noChangeCount());
        value.put("skippedCount", job.skippedCount());
        value.put("failedCount", job.failedCount());
        value.put("attemptCount", job.attemptCount());
        value.put("version", job.version());
        return value;
    }

    private String masked(RuntimeException error) {
        if (error instanceof ItemFailureException failure) {
            return failure.getMessage();
        }
        if (error instanceof BusinessException business) {
            String message = business.getMessage();
            return message.length() <= 400 ? message : message.substring(0, 400);
        }
        return "任务明细执行失败，请稍后重试或联系管理员";
    }

    private String statusLabel(String status) {
        if (BatchTagJob.SUCCEEDED.equals(status)) {
            return "执行成功";
        }
        if (BatchTagJob.PARTIAL_FAILED.equals(status)) {
            return "部分失败";
        }
        if (BatchTagJob.FAILED.equals(status)) {
            return "执行失败";
        }
        return "已完成";
    }

    public record ExecutionIdempotency(String scope, String key, String payload, String triggerType) {
    }

    private static final class ItemFailureException extends RuntimeException {
        private final String code;

        private ItemFailureException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super("任务执行租约已失效");
        }
    }
}
