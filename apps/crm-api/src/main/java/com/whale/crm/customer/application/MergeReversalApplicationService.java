package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.MergeReversalActionRequest;
import com.whale.crm.customer.api.dto.MergeReversalResponse;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerIdentityRepository;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.domain.FollowupRepository;
import com.whale.crm.customer.domain.MergeCase;
import com.whale.crm.customer.domain.MergeCaseRepository;
import com.whale.crm.customer.domain.MergeResourceSnapshot;
import com.whale.crm.customer.domain.MergeResourceSnapshotRepository;
import com.whale.crm.customer.domain.MergeReversal;
import com.whale.crm.customer.domain.MergeReversalRepository;
import com.whale.crm.customer.domain.PointsLedgerRepository;
import com.whale.crm.customer.domain.TaskRepository;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.DataScope;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class MergeReversalApplicationService {
    private final MergeCaseRepository mergeCaseRepository;
    private final MergeReversalRepository reversalRepository;
    private final MergeResourceSnapshotRepository snapshotRepository;
    private final CustomerRepository customerRepository;
    private final CustomerIdentityRepository identityRepository;
    private final CustomerTagRepository tagRepository;
    private final FollowupRepository followupRepository;
    private final TaskRepository taskRepository;
    private final PointsLedgerRepository pointsLedgerRepository;
    private final CustomerOrderOperations customerOrderOperations;
    private final CustomerScopeSupport scopeSupport;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final TransactionTemplate transactions;
    private final MergeReferenceGuard referenceGuard;

    public MergeReversalApplicationService(MergeCaseRepository mergeCaseRepository,
                                           MergeReversalRepository reversalRepository,
                                           MergeResourceSnapshotRepository snapshotRepository,
                                           CustomerRepository customerRepository,
                                           CustomerIdentityRepository identityRepository,
                                           CustomerTagRepository tagRepository,
                                           FollowupRepository followupRepository,
                                           TaskRepository taskRepository,
                                           PointsLedgerRepository pointsLedgerRepository,
                                           CustomerOrderOperations customerOrderOperations,
                                           CustomerScopeSupport scopeSupport,
                                           IdempotencyService idempotencyService,
                                           AuditService auditService,
                                           PlatformTransactionManager transactionManager,
                                           MergeReferenceGuard referenceGuard) {
        this.mergeCaseRepository = mergeCaseRepository;
        this.reversalRepository = reversalRepository;
        this.snapshotRepository = snapshotRepository;
        this.customerRepository = customerRepository;
        this.identityRepository = identityRepository;
        this.tagRepository = tagRepository;
        this.followupRepository = followupRepository;
        this.taskRepository = taskRepository;
        this.pointsLedgerRepository = pointsLedgerRepository;
        this.customerOrderOperations = customerOrderOperations;
        this.scopeSupport = scopeSupport;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.referenceGuard = referenceGuard;
    }

    public MergeReversalResponse get(CurrentUser user, long mergeCaseId) {
        MergeCase mergeCase = mergeCaseRepository.findById(user.tenantId(), mergeCaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在"));
        requireVisibleBoth(user, mergeCase);
        MergeReversal reversal = reversalRepository.findByMergeCaseId(user.tenantId(), mergeCaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "更正申请不存在"));
        return view(reversal);
    }

    public MergeReversalResponse request(CurrentUser user, long mergeCaseId, MergeReversalActionRequest request, String key) {
        String scope = "merge.reversal.request";
        String payload = mergeCaseId + ":" + request.reason();
        if (idempotencyService.lookup(user.tenantId(), scope, key, payload).isPresent()) {
            return get(user, mergeCaseId);
        }
        MergeCase mergeCase = requireConfirmed(user.tenantId(), mergeCaseId);
        requireVisibleBoth(user, mergeCase);
        if (reversalRepository.findByMergeCaseId(user.tenantId(), mergeCaseId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该合并单已存在更正申请");
        }
        Customer primary = customerRepository.findById(user.tenantId(), mergeCase.getPrimaryCustomerId()).orElseThrow();
        Customer secondary = customerRepository.findById(user.tenantId(), mergeCase.getSecondaryCustomerId()).orElseThrow();
        MergeReversal created;
        try {
            created = transactions.execute(status -> {
                MergeReversal input = new MergeReversal(null, user.tenantId(), mergeCaseId, "REQUESTED", request.reason(), null,
                        primary.getVersion(), secondary.getVersion(), user.userId(), Instant.now(), null, null, null, null, null, null);
                reversalRepository.insert(user.tenantId(), input);
                MergeReversal saved = reversalRepository.findByMergeCaseId(user.tenantId(), mergeCaseId).orElseThrow();
                idempotencyService.recordSuccess(user.tenantId(), scope, key, "MERGE_REVERSAL", saved.id().toString(), payload);
                auditService.record(user.tenantId(), user.userId(), "MERGE_REVERSAL_REQUEST", "MERGE_REVERSAL",
                        saved.id().toString(), null, saved, request.reason());
                return saved;
            });
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "该合并单已存在更正申请");
        }
        return view(Objects.requireNonNull(created));
    }

    public MergeReversalResponse approve(CurrentUser user, long reversalId, MergeReversalActionRequest request, String key) {
        String scope = "merge.reversal.approve";
        String payload = reversalId + ":" + request.reason() + ":" + request.version();
        if (idempotencyService.lookup(user.tenantId(), scope, key, payload).isPresent()) {
            return requireAuthorized(user, reversalId);
        }
        try {
            return transactions.execute(status -> doApprove(user, reversalId, request, key, scope, payload));
        } catch (RuntimeException ex) {
            if (!(ex instanceof BusinessException)) {
                markError(user, reversalId);
            }
            throw ex;
        }
    }

    public MergeReversalResponse complete(CurrentUser user, long reversalId, MergeReversalActionRequest request, String key) {
        String scope = "merge.reversal.complete";
        String payload = reversalId + ":" + request.reason() + ":" + request.version();
        if (idempotencyService.lookup(user.tenantId(), scope, key, payload).isPresent()) {
            return requireAuthorized(user, reversalId);
        }
        MergeReversal result = transactions.execute(status -> {
            requireAuthorized(user, reversalId);
            MergeReversal reversal = requireActionable(user.tenantId(), reversalId, request.version(), "MANUAL_REVIEW");
            MergeCase mergeCase = requireConfirmed(user.tenantId(), reversal.mergeCaseId());
            transition(mergeCase, user.userId());
            Instant now = Instant.now();
            update(reversal, "EXECUTED", request.reason(), user.userId(), now, now, reversal.warningMessage(), null);
            MergeReversal saved = reversalRepository.findById(user.tenantId(), reversalId).orElseThrow();
            auditService.record(user.tenantId(), user.userId(), "MERGE_REVERSAL_COMPLETE", "MERGE_REVERSAL",
                    Long.toString(reversalId), reversal, saved, request.reason());
            idempotencyService.recordSuccess(user.tenantId(), scope, key, "MERGE_REVERSAL", Long.toString(reversalId), payload);
            return saved;
        });
        return view(Objects.requireNonNull(result));
    }

    public MergeReversalResponse reject(CurrentUser user, long reversalId, MergeReversalActionRequest request, String key) {
        String scope = "merge.reversal.reject";
        String payload = reversalId + ":" + request.reason() + ":" + request.version();
        if (idempotencyService.lookup(user.tenantId(), scope, key, payload).isPresent()) {
            return requireAuthorized(user, reversalId);
        }
        MergeReversal result = transactions.execute(status -> {
            requireAuthorized(user, reversalId);
            MergeReversal reversal = requireActionable(user.tenantId(), reversalId, request.version(), "REQUESTED");
            Instant now = Instant.now();
            update(reversal, "REJECTED", request.reason(), user.userId(), now, null, null, null);
            MergeReversal saved = reversalRepository.findById(user.tenantId(), reversalId).orElseThrow();
            auditService.record(user.tenantId(), user.userId(), "MERGE_REVERSAL_REJECT", "MERGE_REVERSAL",
                    Long.toString(reversalId), reversal, saved, request.reason());
            idempotencyService.recordSuccess(user.tenantId(), scope, key, "MERGE_REVERSAL", Long.toString(reversalId), payload);
            return saved;
        });
        return view(Objects.requireNonNull(result));
    }

    private MergeReversalResponse doApprove(CurrentUser user, long reversalId, MergeReversalActionRequest request,
                                            String key, String scope, String payload) {
        MergeReversal reversal = requireActionable(user.tenantId(), reversalId, request.version(), "REQUESTED");
        MergeCase mergeCase = requireConfirmed(user.tenantId(), reversal.mergeCaseId());
        requireVisibleBoth(user, mergeCase);
        long primaryId = mergeCase.getPrimaryCustomerId();
        long secondaryId = mergeCase.getSecondaryCustomerId();
        Customer first = lockCustomer(user.tenantId(), Math.min(primaryId, secondaryId));
        Customer second = lockCustomer(user.tenantId(), Math.max(primaryId, secondaryId));
        Customer primary = first.getId() == primaryId ? first : second;
        Customer secondary = first.getId() == secondaryId ? first : second;
        requireVisibleBoth(user, mergeCase);
        if (!primary.getVersion().equals(reversal.primaryVersion()) || !secondary.getVersion().equals(reversal.secondaryVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "客户资料在申请后已变化，请重新评估更正");
        }
        restoreSecondary(secondary);
        List<MergeResourceSnapshot> snapshots = snapshotRepository.findByMergeCaseId(user.tenantId(), mergeCase.getId());
        Instant now = Instant.now();
        if (snapshots.isEmpty()) {
            String warning = "该历史合并没有资源快照：仅恢复了从客户，身份、标签、跟进、任务、积分、订单和权益仍需人工逐项迁移确认。";
            update(reversal, "MANUAL_REVIEW", request.reason(), user.userId(), now, null, warning, null);
        } else {
            referenceGuard.requireUnreferenced(user.tenantId(), ids(snapshots, "ORDER"), ids(snapshots, "ENTITLEMENT"));
            migrateSnapshot(user.tenantId(), mergeCase, snapshots);
            refreshPoints(user.tenantId(), primaryId);
            refreshPoints(user.tenantId(), secondaryId);
            transition(mergeCase, user.userId());
            String warning = "合并时去重删除的身份和标签无法自动恢复，请人工复核是否需要补建。";
            update(reversal, "EXECUTED", request.reason(), user.userId(), now, now, warning, null);
        }
        MergeReversal saved = reversalRepository.findById(user.tenantId(), reversalId).orElseThrow();
        auditService.record(user.tenantId(), user.userId(), "MERGE_REVERSAL_APPROVE", "MERGE_REVERSAL",
                Long.toString(reversalId), reversal, saved, request.reason());
        idempotencyService.recordSuccess(user.tenantId(), scope, key, "MERGE_REVERSAL", Long.toString(reversalId), payload);
        return view(saved);
    }

    private void migrateSnapshot(long tenantId, MergeCase mergeCase, List<MergeResourceSnapshot> snapshots) {
        long primaryId = mergeCase.getPrimaryCustomerId();
        long secondaryId = mergeCase.getSecondaryCustomerId();
        List<Long> identityIds = ids(snapshots, "IDENTITY");
        if (!identityIds.isEmpty()) {
            identityRepository.reassignSpecific(tenantId, identityIds, primaryId, secondaryId);
        }
        List<String> tagCodes = keys(snapshots, "TAG");
        if (!tagCodes.isEmpty()) {
            tagRepository.reassignSpecificCodes(tenantId, tagCodes, primaryId, secondaryId);
        }
        List<Long> followupIds = ids(snapshots, "FOLLOWUP");
        if (!followupIds.isEmpty()) {
            followupRepository.reassignSpecific(tenantId, followupIds, primaryId, secondaryId);
        }
        List<Long> taskIds = ids(snapshots, "TASK");
        if (!taskIds.isEmpty()) {
            taskRepository.reassignSpecific(tenantId, taskIds, primaryId, secondaryId);
        }
        List<Long> pointIds = ids(snapshots, "POINTS");
        if (!pointIds.isEmpty()) {
            pointsLedgerRepository.reassignSpecific(tenantId, pointIds, primaryId, secondaryId);
        }
        customerOrderOperations.reassignSpecific(tenantId, ids(snapshots, "ORDER"), ids(snapshots, "ENTITLEMENT"),
                primaryId, secondaryId);
    }

    private void restoreSecondary(Customer secondary) {
        if (customerRepository.restore(secondary.getTenantId(), secondary.getId(), secondary.getVersion()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "从客户已被恢复或版本已变化");
        }
    }

    private void refreshPoints(long tenantId, long customerId) {
        customerRepository.refreshPointsCached(tenantId, customerId,
                Math.toIntExact(pointsLedgerRepository.sumDelta(tenantId, customerId)));
    }

    private MergeCase requireConfirmed(long tenantId, long caseId) {
        MergeCase mergeCase = mergeCaseRepository.findById(tenantId, caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在"));
        if (!MergeCase.STATUS_CONFIRMED.equals(mergeCase.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有已确认合并才能申请更正");
        }
        return mergeCase;
    }

    private MergeReversal requireActionable(long tenantId, long reversalId, Long version, String expectedStatus) {
        MergeReversal reversal = reversalRepository.findForUpdate(tenantId, reversalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "更正申请不存在"));
        if (!expectedStatus.equals(reversal.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请状态已变化，请刷新后重试");
        }
        if (version == null || !version.equals(reversal.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请版本已变化，请刷新后重试");
        }
        return reversal;
    }

    private void requireVisibleBoth(CurrentUser user, MergeCase mergeCase) {
        DataScope scope = scopeSupport.scope(user);
        for (long customerId : List.of(mergeCase.getPrimaryCustomerId(), mergeCase.getSecondaryCustomerId())) {
            Customer customer = customerRepository.findById(user.tenantId(), customerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
            if (!scopeSupport.isVisible(scope, user, customer)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见");
            }
        }
    }

    private Customer lockCustomer(long tenantId, long customerId) {
        return customerRepository.findByIdForUpdate(tenantId, customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "客户不存在或不可见"));
    }

    private void transition(MergeCase mergeCase, long reviewerId) {
        if (mergeCaseRepository.updateStatus(mergeCase.getTenantId(), mergeCase.getId(), mergeCase.getVersion(),
                MergeCase.STATUS_REVERSED, reviewerId, Instant.now()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "合并单状态已变化，请刷新后重试");
        }
    }

    private void update(MergeReversal reversal, String status, String reviewReason, long reviewerId,
                        Instant reviewedAt, Instant executedAt, String warning, String error) {
        if (reversalRepository.updateStatus(reversal.tenantId(),
                reversal.id(), reversal.version(), status, reviewReason, reviewerId, reviewedAt, executedAt,
                warning, error) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "更正申请版本已变化，请刷新后重试");
        }
    }

    private MergeReversalResponse requireAuthorized(CurrentUser user, long reversalId) {
        MergeReversal reversal = reversalRepository.findById(user.tenantId(), reversalId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "更正申请不存在"));
        MergeCase mergeCase = mergeCaseRepository.findById(user.tenantId(), reversal.mergeCaseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合并单不存在"));
        requireVisibleBoth(user, mergeCase);
        return view(reversal);
    }

    private void markError(CurrentUser user, long reversalId) {
        transactions.executeWithoutResult(status -> reversalRepository.findForUpdate(user.tenantId(), reversalId)
                .ifPresent(reversal -> {
                    String error = "更正执行失败，客户数据未变更，请联系管理员核查后处理";
                    if (reversalRepository.updateStatus(user.tenantId(), reversalId, reversal.version(),
                            "ERROR", reversal.reviewReason(), user.userId(), Instant.now(), null,
                            reversal.warningMessage(), error) == 1) {
                        MergeReversal saved = reversalRepository.findById(user.tenantId(), reversalId).orElseThrow();
                        auditService.record(user.tenantId(), user.userId(), "MERGE_REVERSAL_ERROR", "MERGE_REVERSAL",
                                Long.toString(reversalId), reversal, saved, "执行失败，已回滚客户数据变更");
                    }
                }));
    }

    private static List<Long> ids(List<MergeResourceSnapshot> snapshots, String type) {
        return snapshots.stream().filter(item -> item.resourceType().equals(type))
                .map(item -> Long.parseLong(item.resourceKey())).toList();
    }

    private static List<String> keys(List<MergeResourceSnapshot> snapshots, String type) {
        return snapshots.stream().filter(item -> item.resourceType().equals(type))
                .map(MergeResourceSnapshot::resourceKey).toList();
    }

    private MergeReversalResponse view(MergeReversal reversal) {
        return new MergeReversalResponse(reversal.id(), reversal.mergeCaseId(), reversal.status(),
                reversal.warningMessage(), reversal.version());
    }
}
