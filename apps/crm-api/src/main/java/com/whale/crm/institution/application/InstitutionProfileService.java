package com.whale.crm.institution.application;

import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateInstitutionProfileRequest;
import com.whale.crm.institution.api.dto.CreateProfileChangeRequest;
import com.whale.crm.institution.api.dto.CreateWorkflowRequest;
import com.whale.crm.institution.api.dto.InstitutionProfileResponse;
import com.whale.crm.institution.api.dto.UpdateInstitutionProfileRequest;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.domain.OrdinaryProfileValues;
import com.whale.crm.institution.domain.ProfileChangeRequest;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class InstitutionProfileService {
    private static final String CREATE_SCOPE = "institution.profile.create";
    private static final String UPDATE_SCOPE = "institution.profile.update";
    private static final String ACTIVATE_SCOPE = "institution.profile.activation";
    private static final String FIELD_SCOPE = "institution.profile.field-change";

    private final InstitutionRepository repository;
    private final InstitutionSupport support;
    private final InstitutionChangeService changeService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionProfileService(InstitutionRepository repository, InstitutionSupport support,
                                     InstitutionChangeService changeService, AuditService auditService,
                                     OutboxService outboxService, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.support = support;
        this.changeService = changeService;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<InstitutionProfileResponse> search(long tenantId, CurrentUser user, String keyword,
                                                           String merchantKeyword, String status, String cityCode,
                                                           int page, int size) {
        InstitutionScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        List<InstitutionProfileResponse> items = repository.searchProfiles(tenantId, scope, keyword, merchantKeyword,
                        status, cityCode, offset, size).stream().map(profile -> support.response(profile, user)).toList();
        return new PageResponse<>(items,
                repository.countProfiles(tenantId, scope, keyword, merchantKeyword, status, cityCode), page, size);
    }

    @Transactional(readOnly = true)
    public InstitutionProfileResponse detail(long tenantId, CurrentUser user, long id) {
        InstitutionProfile profile = repository.findProfile(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在或不可见"));
        support.requireVisible(tenantId, profile, user);
        return support.response(profile, user);
    }

    public InstitutionProfileResponse create(long tenantId, CurrentUser user,
                                             CreateInstitutionProfileRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(request);
        IdempotencyRecord replay = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay != null) {
            return detail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doCreate(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayProfile(tenantId, user, key, payload));
        }
    }

    public InstitutionProfileResponse update(long tenantId, CurrentUser user, long id,
                                             UpdateInstitutionProfileRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new UpdateCanonical(id, request.version(), request.shortName(), request.cityCode(),
                request.address(), request.contactName(), request.contactPhone(), request.introduction()));
        IdempotencyRecord replay = support.lookup(tenantId, UPDATE_SCOPE, key, payload);
        if (replay != null) {
            return detail(tenantId, user, id);
        }
        try {
            return transactionTemplate.execute(status -> doUpdate(tenantId, user, id, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayUpdate(tenantId, user, id, key, payload));
        }
    }

    public ChangeRequestResponse activationRequest(long tenantId, CurrentUser user, long id,
                                                   CreateWorkflowRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new WorkflowCanonical(id, request.baseVersion(), request.reason(), request.evidenceFileId()));
        IdempotencyRecord replay = support.lookup(tenantId, ACTIVATE_SCOPE, key, payload);
        if (replay != null) {
            return changeService.detail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doActivation(tenantId, user, id, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayChange(tenantId, user, ACTIVATE_SCOPE, key, payload));
        }
    }

    public ChangeRequestResponse fieldChange(long tenantId, CurrentUser user,
                                             CreateProfileChangeRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(request);
        IdempotencyRecord replay = support.lookup(tenantId, FIELD_SCOPE, key, payload);
        if (replay != null) {
            return changeService.detail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doFieldChange(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayChange(tenantId, user, FIELD_SCOPE, key, payload));
        }
    }

    private InstitutionProfileResponse doCreate(long tenantId, CurrentUser user,
                                                CreateInstitutionProfileRequest request, String key, String payload) {
        support.requireEmployeeWrite(tenantId, user);
        if (support.lookup(tenantId, CREATE_SCOPE, key, payload) != null) {
            return replayProfile(tenantId, user, key, payload);
        }
        InstitutionProfile input = new InstitutionProfile(null, request.institutionNo().strip(), request.fullName().strip(),
                request.shortName(), request.cityCode(), request.businessTypeCode(), request.ownershipCode(),
                request.gradeCode(), request.address(), request.contactName(), support.encrypt(request.contactPhone()),
                "PENDING", request.settlementType(), request.onlineRate(), request.offlineRate(), request.platformRate(),
                request.otherRateRule(), request.chargesFee() != null && request.chargesFee(),
                request.depositFen() == null ? Long.valueOf(0L) : request.depositFen(),
                request.guaranteeRoi() != null && request.guaranteeRoi(), request.roiTerms(), request.settlementName(),
                support.encrypt(request.bankName()), support.encrypt(request.bankAccount()), support.encrypt(request.taxId()),
                request.introduction(), null, null);
        repository.insertProfile(input);
        InstitutionProfile created = repository.findProfileByNo(tenantId, input.institutionNo()).orElseThrow();
        outboxService.append(tenantId, "institution.profile.created.v1:" + created.id(), "institution.profile.created.v1",
                "institution", Long.toString(created.id()), Map.of("institutionId", created.id(), "status", created.status()));
        auditService.record(tenantId, user.userId(), "INSTITUTION_PROFILE_CREATE", "INSTITUTION",
                Long.toString(created.id()), null, support.response(created, user), null);
        support.record(tenantId, CREATE_SCOPE, key, "INSTITUTION", created.id(), payload);
        return support.response(created, user);
    }

    private InstitutionProfileResponse doUpdate(long tenantId, CurrentUser user, long id,
                                                UpdateInstitutionProfileRequest request, String key, String payload) {
        InstitutionProfile before = repository.findProfileForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        support.requireEmployeeWrite(tenantId, user);
        support.requireVisible(tenantId, before, user);
        if (support.lookup(tenantId, UPDATE_SCOPE, key, payload) != null) {
            return support.response(before, user);
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构版本已变化，请刷新后重试");
        }
        OrdinaryProfileValues values = new OrdinaryProfileValues(
                request.shortName() == null ? before.shortName() : request.shortName(),
                request.cityCode() == null ? before.cityCode() : request.cityCode(),
                request.address() == null ? before.address() : request.address(),
                request.contactName() == null ? before.contactName() : request.contactName(),
                request.contactPhone() == null ? before.contactPhoneCipher() : support.encrypt(request.contactPhone()),
                request.introduction() == null ? before.introduction() : request.introduction());
        if (repository.updateProfileOrdinary(tenantId, id, request.version(), values) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构版本冲突");
        }
        InstitutionProfile after = repository.findProfile(tenantId, id).orElseThrow();
        outboxService.append(tenantId, "institution.profile.updated.v1:" + id + ":" + after.version(),
                "institution.profile.updated.v1", "institution", Long.toString(id),
                Map.of("institutionId", id, "version", after.version()));
        auditService.record(tenantId, user.userId(), "INSTITUTION_PROFILE_UPDATE", "INSTITUTION", Long.toString(id),
                support.response(before, user), support.response(after, user), null);
        support.record(tenantId, UPDATE_SCOPE, key, "INSTITUTION", id, payload);
        return support.response(after, user);
    }

    private ChangeRequestResponse doActivation(long tenantId, CurrentUser user, long id,
                                               CreateWorkflowRequest request, String key, String payload) {
        InstitutionProfile profile = repository.findProfileForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        support.requireChange(tenantId, profile, user);
        if (!"PENDING".equals(profile.status()) || !profile.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有版本一致的PENDING机构可申请激活");
        }
        requireComplete(profile);
        support.requireClean(tenantId, request.evidenceFileId());
        ProfileChangeRequest draft = workflowDraft(profile, "ONBOARDING", null, id,
                "PENDING", "ACTIVE", request.reason(), request.evidenceFileId(), "MARKET", user);
        ChangeRequestResponse response = changeService.create(tenantId, user, draft);
        support.record(tenantId, ACTIVATE_SCOPE, key, "INSTITUTION_CHANGE", response.id(), payload);
        return response;
    }

    private ChangeRequestResponse doFieldChange(long tenantId, CurrentUser user,
                                                CreateProfileChangeRequest request, String key, String payload) {
        InstitutionProfile profile = repository.findProfileForUpdate(tenantId, request.institutionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在"));
        support.requireChange(tenantId, profile, user);
        if (!profile.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "baseVersion与机构当前版本不一致");
        }
        String normalized = InstitutionFieldPolicy.normalize(request.fieldCode(), request.newValue());
        Object before = InstitutionFieldPolicy.currentValue(profile, request.fieldCode(), support);
        support.requireClean(tenantId, request.evidenceFileId());
        String beforeMasked = support.jsonText(Collections.singletonMap("value",
                support.maskValue(request.fieldCode(), before)));
        String afterMasked = support.jsonText(Collections.singletonMap("value",
                support.maskValue(request.fieldCode(), normalized)));
        String beforeCipher = support.encrypt(support.jsonText(before));
        String afterCipher = support.encrypt(support.jsonText(InstitutionFieldPolicy.jsonValue(request.fieldCode(), normalized)));
        String step = InstitutionFieldPolicy.firstStep(request.fieldCode());
        ProfileChangeRequest draft = new ProfileChangeRequest(null, profile.id(), "PROFILE_FIELD", request.fieldCode(),
                profile.id(), request.baseVersion(), profile.version(), beforeCipher, afterCipher, beforeMasked, afterMasked,
                request.reason(), request.evidenceFileId(), "PENDING_REVIEW", step, user.userId(), Instant.now(), null,
                InstitutionChangeService.activeTarget("PROFILE_FIELD", profile.id(), request.fieldCode()), null, null);
        ChangeRequestResponse response = changeService.create(tenantId, user, draft);
        support.record(tenantId, FIELD_SCOPE, key, "INSTITUTION_CHANGE", response.id(), payload);
        return response;
    }

    private ProfileChangeRequest workflowDraft(InstitutionProfile profile, String type, String fieldCode,
                                               long targetId, String before, String after, String reason,
                                               Long evidenceFileId, String step, CurrentUser user) {
        return new ProfileChangeRequest(null, profile.id(), type, fieldCode, targetId, profile.version(), profile.version(),
                support.encrypt(support.jsonText(before)), support.encrypt(support.jsonText(after)),
                support.jsonText(Collections.singletonMap("status", before)),
                support.jsonText(Collections.singletonMap("status", after)), reason, evidenceFileId,
                "PENDING_REVIEW", step, user.userId(), Instant.now(), null,
                InstitutionChangeService.activeTarget(type, targetId, fieldCode), null, null);
    }

    private void requireComplete(InstitutionProfile profile) {
        if (profile.fullName() == null || profile.businessTypeCode() == null || profile.ownershipCode() == null
                || profile.gradeCode() == null || profile.settlementType() == null
                || profile.onlineRate() == null || profile.offlineRate() == null || profile.platformRate() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "机构名称、类型、等级、结算与费率资料完整后才能激活");
        }
    }

    private InstitutionProfileResponse replayProfile(long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构创建冲突且没有已提交幂等结果");
        }
        return detail(tenantId, user, support.resourceId(record));
    }

    private InstitutionProfileResponse replayUpdate(long tenantId, CurrentUser user, long id,
                                                    String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, UPDATE_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构更新冲突且没有已提交幂等结果");
        }
        return detail(tenantId, user, id);
    }

    private ChangeRequestResponse replayChange(long tenantId, CurrentUser user, String scope,
                                               String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, scope, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构变更申请冲突且没有已提交幂等结果");
        }
        return changeService.detail(tenantId, user, support.resourceId(record));
    }

    private record UpdateCanonical(long id, long version, String shortName, String cityCode, String address,
                                   String contactName, String contactPhone, String introduction) {
    }

    private record WorkflowCanonical(long id, long baseVersion, String reason, Long evidenceFileId) {
    }
}
