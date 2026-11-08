package com.whale.crm.institution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.RevalidateChangeRequest;
import com.whale.crm.institution.api.dto.ReviewChangeRequest;
import com.whale.crm.institution.domain.InstitutionContract;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.domain.ProfileChangeRequest;
import com.whale.crm.institution.domain.ProfileChangeStep;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.trading.application.OutboxService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class InstitutionChangeService {
    private static final String REVIEW_SCOPE = "institution.change.review";
    private static final String REVALIDATE_SCOPE = "institution.change.revalidate";

    private final InstitutionRepository repository;
    private final InstitutionSupport support;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionChangeService(InstitutionRepository repository, InstitutionSupport support,
                                    AuditService auditService, OutboxService outboxService,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<ChangeRequestResponse> search(long tenantId, CurrentUser user, Long institutionId,
                                                      String status, int page, int size) {
        InstitutionScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        List<ChangeRequestResponse> items = repository.searchChanges(tenantId, scope, institutionId, status, offset, size)
                .stream().map(this::detailResponse).toList();
        return new PageResponse<>(items, repository.countChanges(tenantId, scope, institutionId, status), page, size);
    }

    @Transactional(readOnly = true)
    public ChangeRequestResponse detail(long tenantId, CurrentUser user, long id) {
        ProfileChangeRequest request = requireChange(tenantId, user, id);
        return detailResponse(request);
    }

    @Transactional
    public ChangeRequestResponse create(long tenantId, CurrentUser user, ProfileChangeRequest draft) {
        if (repository.findActiveChange(tenantId, draft.activeTarget()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该机构目标已有进行中的变更申请");
        }
        repository.insertChange(draft);
        ProfileChangeRequest created = repository.findActiveChange(tenantId, draft.activeTarget()).orElseThrow();
        insertStep(tenantId, created.id(), "REQUESTED", "REQUESTED", user.userId(), created.reason());
        outbox(tenantId, created, "requested", created.status());
        auditService.record(tenantId, user.userId(), "INSTITUTION_CHANGE_REQUEST", "INSTITUTION",
                Long.toString(created.institutionId()), null, support.auditChange(created), created.reason());
        return detailResponse(created);
    }

    public ChangeRequestResponse review(long tenantId, CurrentUser user, long id, String step,
                                        ReviewChangeRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new ReviewCanonical(id, step, request.version(), request.decision(), request.comment()));
        IdempotencyRecord replay = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (replay != null) {
            return detail(tenantId, user, id);
        }
        try {
            return transactionTemplate.execute(status -> doReview(tenantId, user, id, step, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayReview(tenantId, user, id, key, payload));
        }
    }

    public ChangeRequestResponse revalidate(long tenantId, CurrentUser user, long id,
                                            RevalidateChangeRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new RevalidateCanonical(id, request.version(), request.baseVersion(), request.reason()));
        IdempotencyRecord replay = support.lookup(tenantId, REVALIDATE_SCOPE, key, payload);
        if (replay != null) {
            return detail(tenantId, user, id);
        }
        try {
            return transactionTemplate.execute(status -> doRevalidate(tenantId, user, id, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayRevalidate(tenantId, user, id, key, payload));
        }
    }

    private ChangeRequestResponse doReview(long tenantId, CurrentUser user, long id, String step,
                                           ReviewChangeRequest request, String key, String payload) {
        ProfileChangeRequest before = repository.findChangeForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "变更申请不存在"));
        InstitutionProfile institution = repository.findProfileForUpdate(tenantId, before.institutionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        support.requireVisible(tenantId, institution, user);
        if (!"PENDING_REVIEW".equals(before.status())) {
            IdempotencyRecord terminalReplay = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
            if (terminalReplay != null) {
                return detailResponse(before);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "申请已审核或拒绝，不能再次审批");
        }
        support.requirePermission(user, "MARKET".equals(step) ? "institution:approve_market" : "institution:approve_finance");
        if (before.requestedBy().equals(user.userId())) {
            denySelfReview(tenantId, user, before, "申请人不能审核本人机构变更");
        }
        if (requiresBoth(before) && "FINANCE".equals(step)) {
            repository.findSteps(tenantId, id).stream()
                    .filter(item -> "MARKET".equals(item.stepCode()) && "APPROVED".equals(item.status()))
                    .filter(item -> item.reviewerUserId().equals(user.userId())).findFirst()
                    .ifPresent(item -> denySelfReview(tenantId, user, before, "双审必须由两位不同审核人完成"));
        }
        IdempotencyRecord replay = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (replay != null) {
            return detailResponse(before);
        }
        if (!before.currentStep().equals(step)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "当前审批步骤不允许该角色或顺序操作");
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请版本已变化，请刷新后重试");
        }
        if (!institution.version().equals(before.baseVersion()) || targetVersionMismatch(tenantId, before)) {
            return transition(tenantId, user, before, "CONFLICT_REVALIDATE", "CONFLICT",
                    step, "CONFLICT_REVALIDATE", request.comment(), key, payload);
        }
        if ("REJECT".equals(request.decision())) {
            rejectTarget(tenantId, user, before);
            return transition(tenantId, user, before, "REJECTED", "COMPLETED",
                    step, "REJECTED", request.comment(), key, payload);
        }
        if (requiresBoth(before) && "MARKET".equals(step)) {
            return transition(tenantId, user, before, "PENDING_REVIEW", "FINANCE",
                    step, "APPROVED", request.comment(), key, payload);
        }
        applyFinal(tenantId, user, before);
        return transition(tenantId, user, before, "APPROVED", "COMPLETED",
                step, "APPROVED", request.comment(), key, payload);
    }

    private ChangeRequestResponse transition(long tenantId, CurrentUser user, ProfileChangeRequest before,
                                             String toStatus, String currentStep, String historyStep,
                                             String historyStatus, String comment, String key, String payload) {
        Instant now = Instant.now();
        String activeTarget = List.of("APPROVED", "REJECTED", "CONFLICT_REVALIDATE").contains(toStatus)
                ? null : before.activeTarget();
        Instant completedAt = activeTarget == null ? now : null;
        if (repository.updateChangeStatus(tenantId, before.id(), before.version(), before.status(), toStatus,
                currentStep, completedAt, activeTarget) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请已被其他审核人处理");
        }
        insertStep(tenantId, before.id(), historyStep, historyStatus, user.userId(), comment);
        ProfileChangeRequest after = repository.findChange(tenantId, before.id()).orElseThrow();
        outbox(tenantId, after, "reviewed", toStatus);
        auditService.record(tenantId, user.userId(), "INSTITUTION_CHANGE_REVIEW", "INSTITUTION",
                Long.toString(before.institutionId()), support.auditChange(before), support.auditChange(after), comment);
        support.record(tenantId, REVIEW_SCOPE, key, "INSTITUTION_CHANGE", before.id(), payload);
        return detailResponse(after);
    }

    private ChangeRequestResponse doRevalidate(long tenantId, CurrentUser user, long id,
                                               RevalidateChangeRequest request, String key, String payload) {
        ProfileChangeRequest before = repository.findChangeForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "变更申请不存在"));
        InstitutionProfile institution = repository.findProfileForUpdate(tenantId, before.institutionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        support.requireChange(tenantId, institution, user);
        IdempotencyRecord replay = support.lookup(tenantId, REVALIDATE_SCOPE, key, payload);
        if (replay != null) {
            return detailResponse(before);
        }
        if (!"CONFLICT_REVALIDATE".equals(before.status()) || !before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有冲突待复核申请且版本一致才能重新复核");
        }
        if (!institution.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "baseVersion仍不是机构当前版本，请刷新后再复核");
        }
        long targetVersion = currentTargetVersion(tenantId, before);
        if (repository.rebaseChange(tenantId, id, request.version(), request.baseVersion(), targetVersion,
                firstStep(before), activeTarget(before.changeType(), before.targetId(), before.fieldCode())) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "申请复核状态已变化");
        }
        insertStep(tenantId, id, "REVALIDATE", "PENDING_REVIEW", user.userId(), request.reason());
        ProfileChangeRequest after = repository.findChange(tenantId, id).orElseThrow();
        outbox(tenantId, after, "revalidated", after.status());
        auditService.record(tenantId, user.userId(), "INSTITUTION_CHANGE_REVALIDATE", "INSTITUTION",
                Long.toString(before.institutionId()), support.auditChange(before), support.auditChange(after), request.reason());
        support.record(tenantId, REVALIDATE_SCOPE, key, "INSTITUTION_CHANGE", id, payload);
        return detailResponse(after);
    }

    private void applyFinal(long tenantId, CurrentUser user, ProfileChangeRequest request) {
        support.requireClean(tenantId, request.evidenceFileId());
        switch (request.changeType()) {
            case "ONBOARDING" -> {
                if (repository.updateProfileStatus(tenantId, request.institutionId(), request.baseVersion(),
                        "PENDING", "ACTIVE") == 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "机构激活版本冲突");
                }
            }
            case "PROFILE_FIELD" -> {
                JsonNode after = support.json(support.decrypt(request.afterCipher()));
                String normalized = InstitutionFieldPolicy.normalize(request.fieldCode(), after);
                String storage = InstitutionFieldPolicy.storageValue(request.fieldCode(), normalized, support);
                if (repository.applyProfileField(tenantId, request.institutionId(), request.baseVersion(),
                        request.fieldCode(), storage) == 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "机构字段版本冲突，禁止覆盖较新值");
                }
            }
            case "CONTRACT_ACTIVATION" -> {
                InstitutionContract target = repository.findContractForUpdate(tenantId, request.targetId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合同不存在"));
                support.requireClean(tenantId, target.fileId());
                if (target.previousContractId() != null) {
                    InstitutionContract previous = repository.findContractForUpdate(tenantId, target.previousContractId())
                            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上一合同版本不存在"));
                    if (!"ACTIVE".equals(previous.status())
                            || repository.updateContractStatus(tenantId, previous.id(), previous.version(),
                            "ACTIVE", "SUPERSEDED") == 0) {
                        throw new BusinessException(ErrorCode.CONFLICT, "上一合同版本已变化，不能覆盖历史");
                    }
                }
                if (!"PENDING_REVIEW".equals(target.status())
                        || repository.updateContractStatus(tenantId, target.id(), request.targetVersion(),
                        "PENDING_REVIEW", "ACTIVE") == 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "合同激活版本冲突");
                }
                touchInstitution(tenantId, request);
            }
            case "QUALIFICATION_REVIEW" -> {
                InstitutionQualification qualification = repository.findQualificationForUpdate(tenantId, request.targetId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资质不存在"));
                support.requireClean(tenantId, qualification.fileId());
                if (repository.updateQualificationStatus(tenantId, request.targetId(), request.targetVersion(),
                        "PENDING_REVIEW", "APPROVED", user.userId(), Instant.now()) == 0) {
                    throw new BusinessException(ErrorCode.CONFLICT, "资质审核版本冲突");
                }
                touchInstitution(tenantId, request);
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知机构变更类型");
        }
    }

    private void rejectTarget(long tenantId, CurrentUser user, ProfileChangeRequest request) {
        if ("CONTRACT_ACTIVATION".equals(request.changeType())
                && repository.updateContractStatus(tenantId, request.targetId(), request.targetVersion(),
                "PENDING_REVIEW", "REJECTED") == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "合同驳回版本冲突");
        }
        if ("QUALIFICATION_REVIEW".equals(request.changeType())
                && repository.updateQualificationStatus(tenantId, request.targetId(), request.targetVersion(),
                "PENDING_REVIEW", "REJECTED", user.userId(), Instant.now()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "资质驳回版本冲突");
        }
        if (List.of("CONTRACT_ACTIVATION", "QUALIFICATION_REVIEW").contains(request.changeType())) {
            touchInstitution(tenantId, request);
        }
    }

    private boolean targetVersionMismatch(long tenantId, ProfileChangeRequest request) {
        return switch (request.changeType()) {
            case "CONTRACT_ACTIVATION" -> repository.findContractForUpdate(tenantId, request.targetId())
                    .map(InstitutionContract::version).stream().anyMatch(version -> !version.equals(request.targetVersion()));
            case "QUALIFICATION_REVIEW" -> repository.findQualificationForUpdate(tenantId, request.targetId())
                    .map(InstitutionQualification::version).stream().anyMatch(version -> !version.equals(request.targetVersion()));
            default -> false;
        };
    }

    private long currentTargetVersion(long tenantId, ProfileChangeRequest request) {
        return switch (request.changeType()) {
            case "CONTRACT_ACTIVATION" -> repository.findContractForUpdate(tenantId, request.targetId())
                    .orElseThrow().version();
            case "QUALIFICATION_REVIEW" -> repository.findQualificationForUpdate(tenantId, request.targetId())
                    .orElseThrow().version();
            default -> repository.findProfileForUpdate(tenantId, request.institutionId()).orElseThrow().version();
        };
    }

    private void touchInstitution(long tenantId, ProfileChangeRequest request) {
        if (repository.touchInstitution(tenantId, request.institutionId(), request.baseVersion()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构版本冲突");
        }
    }

    public static String activeTarget(String changeType, long targetId, String fieldCode) {
        return changeType + ":" + targetId + (fieldCode == null ? "" : ":" + fieldCode);
    }

    private boolean requiresBoth(ProfileChangeRequest request) {
        return switch (request.changeType()) {
            case "ONBOARDING", "CONTRACT_ACTIVATION" -> true;
            case "QUALIFICATION_REVIEW" -> false;
            default -> InstitutionFieldPolicy.bothSteps(request.fieldCode());
        };
    }

    private String firstStep(ProfileChangeRequest request) {
        return switch (request.changeType()) {
            case "ONBOARDING", "CONTRACT_ACTIVATION", "QUALIFICATION_REVIEW" -> "MARKET";
            default -> InstitutionFieldPolicy.firstStep(request.fieldCode());
        };
    }

    private ProfileChangeRequest requireChange(long tenantId, CurrentUser user, long id) {
        ProfileChangeRequest request = repository.findChange(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "变更申请不存在或不可见"));
        InstitutionProfile institution = repository.findProfile(tenantId, request.institutionId()).orElseThrow();
        support.requireVisible(tenantId, institution, user);
        return request;
    }

    private ChangeRequestResponse detailResponse(ProfileChangeRequest request) {
        return support.response(request,
                repository.findSteps(com.whale.crm.platform.context.TenantContext.currentTenantId(), request.id()));
    }

    private void insertStep(long tenantId, long requestId, String step, String status, long actor, String comment) {
        int sequence = repository.findSteps(tenantId, requestId).size();
        repository.insertStep(new ProfileChangeStep(null, requestId, sequence, step, status, actor,
                Instant.now(), comment, null));
    }

    private void denySelfReview(long tenantId, CurrentUser user, ProfileChangeRequest request, String reason) {
        auditService.recordIndependent(tenantId, user.userId(), "INSTITUTION_SELF_REVIEW_DENIED", "INSTITUTION",
                Long.toString(request.institutionId()), null, support.auditChange(request), reason);
        throw new BusinessException(ErrorCode.FORBIDDEN, reason);
    }

    private void outbox(long tenantId, ProfileChangeRequest request, String action, String status) {
        outboxService.append(tenantId, "institution.change." + action + ".v1:" + request.id() + ":" + request.version(),
                "institution.change." + action + ".v1", "institution", Long.toString(request.institutionId()),
                Map.of("requestId", request.id(), "institutionId", request.institutionId(),
                        "changeType", request.changeType(), "status", status));
    }

    private ChangeRequestResponse replayReview(long tenantId, CurrentUser user, long id, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, REVIEW_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构审核冲突且没有已提交幂等结果");
        }
        return detail(tenantId, user, id);
    }

    private ChangeRequestResponse replayRevalidate(long tenantId, CurrentUser user, long id,
                                                   String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, REVALIDATE_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构复核冲突且没有已提交幂等结果");
        }
        return detail(tenantId, user, id);
    }

    private record ReviewCanonical(long id, String step, long version, String decision, String comment) {
    }

    private record RevalidateCanonical(long id, long version, long baseVersion, String reason) {
    }
}
