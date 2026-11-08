package com.whale.crm.institution.application;

import com.whale.crm.institution.api.dto.ChangeRequestResponse;
import com.whale.crm.institution.api.dto.CreateContractRequest;
import com.whale.crm.institution.api.dto.CreateQualificationRequest;
import com.whale.crm.institution.api.dto.CreateWorkflowRequest;
import com.whale.crm.institution.api.dto.QualificationResponse;
import com.whale.crm.institution.domain.InstitutionContract;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionQualification;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.InstitutionScope;
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
import java.util.List;
import java.util.Map;

@Service
public class InstitutionDocumentService {
    private static final String CONTRACT_SCOPE = "institution.contract.create";
    private static final String CONTRACT_ACTIVATE_SCOPE = "institution.contract.activation";
    private static final String QUALIFICATION_SCOPE = "institution.qualification.create";
    private static final String QUALIFICATION_REVIEW_SCOPE = "institution.qualification.review";

    private final InstitutionRepository repository;
    private final InstitutionSupport support;
    private final InstitutionChangeService changeService;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionDocumentService(InstitutionRepository repository, InstitutionSupport support,
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
    public PageResponse<InstitutionContract> contracts(long tenantId, CurrentUser user, long institutionId,
                                                       String status, int page, int size) {
        requireVisible(tenantId, user, institutionId);
        InstitutionScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchContracts(tenantId, scope, institutionId, status, offset, size),
                repository.countContracts(tenantId, scope, institutionId, status), page, size);
    }

    @Transactional(readOnly = true)
    public InstitutionContract contract(long tenantId, CurrentUser user, long id) {
        InstitutionContract contract = repository.findContract(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合同不存在或不可见"));
        requireVisible(tenantId, user, contract.institutionId());
        return contract;
    }

    @Transactional(readOnly = true)
    public PageResponse<QualificationResponse> qualifications(long tenantId, CurrentUser user, long institutionId,
                                                              String status, int page, int size) {
        requireVisible(tenantId, user, institutionId);
        InstitutionScope scope = support.scope(tenantId, user);
        int offset = (page - 1) * size;
        List<QualificationResponse> items = repository.searchQualifications(tenantId, scope, institutionId,
                status, offset, size).stream().map(support::response).toList();
        return new PageResponse<>(items, repository.countQualifications(tenantId, scope, institutionId, status), page, size);
    }

    @Transactional(readOnly = true)
    public QualificationResponse qualification(long tenantId, CurrentUser user, long id) {
        InstitutionQualification qualification = repository.findQualification(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资质不存在或不可见"));
        requireVisible(tenantId, user, qualification.institutionId());
        return support.response(qualification);
    }

    public InstitutionContract createContract(long tenantId, CurrentUser user,
                                              CreateContractRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(request);
        IdempotencyRecord replay = support.lookup(tenantId, CONTRACT_SCOPE, key, payload);
        if (replay != null) {
            return contract(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doCreateContract(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayContract(tenantId, user, key, payload));
        }
    }

    public ChangeRequestResponse activateContract(long tenantId, CurrentUser user, long id,
                                                  CreateWorkflowRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new WorkflowCanonical(id, request.baseVersion(), request.reason(), request.evidenceFileId()));
        IdempotencyRecord replay = support.lookup(tenantId, CONTRACT_ACTIVATE_SCOPE, key, payload);
        if (replay != null) {
            return changeService.detail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doActivateContract(tenantId, user, id, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayChange(tenantId, user, CONTRACT_ACTIVATE_SCOPE, key, payload));
        }
    }

    public QualificationResponse createQualification(long tenantId, CurrentUser user,
                                                     CreateQualificationRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(request);
        IdempotencyRecord replay = support.lookup(tenantId, QUALIFICATION_SCOPE, key, payload);
        if (replay != null) {
            return qualification(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doCreateQualification(tenantId, user, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayQualification(tenantId, user, key, payload));
        }
    }

    public ChangeRequestResponse reviewQualification(long tenantId, CurrentUser user, long id,
                                                     CreateWorkflowRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new WorkflowCanonical(id, request.baseVersion(), request.reason(), request.evidenceFileId()));
        IdempotencyRecord replay = support.lookup(tenantId, QUALIFICATION_REVIEW_SCOPE, key, payload);
        if (replay != null) {
            return changeService.detail(tenantId, user, support.resourceId(replay));
        }
        try {
            return transactionTemplate.execute(status -> doReviewQualification(tenantId, user, id, request, key, payload));
        } catch (DuplicateKeyException ex) {
            return transactionTemplate.execute(status -> replayChange(tenantId, user, QUALIFICATION_REVIEW_SCOPE, key, payload));
        }
    }

    private InstitutionContract doCreateContract(long tenantId, CurrentUser user,
                                                 CreateContractRequest request, String key, String payload) {
        InstitutionProfile profile = requireVisible(tenantId, user, request.institutionId());
        support.requireChange(tenantId, profile, user);
        support.requireClean(tenantId, request.fileId());
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "合同结束日期不能早于开始日期");
        }
        String contractNo = request.contractNo().strip();
        int versionNo = 1;
        Long previousId = null;
        if (request.previousContractId() != null) {
            InstitutionContract previous = repository.findContractForUpdate(tenantId, request.previousContractId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上一合同版本不存在"));
            if (!previous.institutionId().equals(request.institutionId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "上一合同版本不属于该机构");
            }
            if (!"ACTIVE".equals(previous.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "只有当前ACTIVE合同可创建下一版本");
            }
            previousId = previous.id();
            contractNo = previous.contractNo();
            versionNo = previous.versionNo() + 1;
        }
        InstitutionContract input = new InstitutionContract(null, request.institutionId(), contractNo, versionNo,
                request.title(), request.contractType(), request.startDate(), request.endDate(), request.fileId(),
                request.amountFen(), request.notes(), "PENDING_REVIEW", previousId, user.userId(), null, null);
        repository.insertContract(input);
        InstitutionContract created = repository.findContractByKey(tenantId, contractNo, versionNo).orElseThrow();
        outboxService.append(tenantId, "institution.contract.created.v1:" + created.id(), "institution.contract.created.v1",
                "institution", Long.toString(created.institutionId()),
                Map.of("institutionId", created.institutionId(), "contractId", created.id(), "versionNo", versionNo));
        auditService.record(tenantId, user.userId(), "INSTITUTION_CONTRACT_CREATE", "INSTITUTION",
                Long.toString(created.institutionId()), null, created, request.notes());
        support.record(tenantId, CONTRACT_SCOPE, key, "INSTITUTION_CONTRACT", created.id(), payload);
        return created;
    }

    private ChangeRequestResponse doActivateContract(long tenantId, CurrentUser user, long id,
                                                     CreateWorkflowRequest request, String key, String payload) {
        InstitutionContract contract = repository.findContractForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "合同不存在"));
        InstitutionProfile profile = requireVisible(tenantId, user, contract.institutionId());
        support.requireChange(tenantId, profile, user);
        if (!"PENDING_REVIEW".equals(contract.status()) || !contract.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "合同已处理或版本不一致");
        }
        support.requireClean(tenantId, contract.fileId());
        support.requireClean(tenantId, request.evidenceFileId());
        String beforeMasked = support.jsonText(Map.of("status", contract.status()));
        String afterMasked = support.jsonText(Map.of("status", "ACTIVE"));
        ProfileChangeRequest draft = new ProfileChangeRequest(null, contract.institutionId(), "CONTRACT_ACTIVATION",
                null, id, profile.version(), contract.version(), null, null, beforeMasked, afterMasked,
                request.reason(), request.evidenceFileId(), "PENDING_REVIEW", "MARKET", user.userId(), Instant.now(), null,
                InstitutionChangeService.activeTarget("CONTRACT_ACTIVATION", id, null), null, null);
        ChangeRequestResponse response = changeService.create(tenantId, user, draft);
        support.record(tenantId, CONTRACT_ACTIVATE_SCOPE, key, "INSTITUTION_CHANGE", response.id(), payload);
        return response;
    }

    private QualificationResponse doCreateQualification(long tenantId, CurrentUser user,
                                                        CreateQualificationRequest request, String key, String payload) {
        InstitutionProfile profile = requireVisible(tenantId, user, request.institutionId());
        support.requireChange(tenantId, profile, user);
        support.requireClean(tenantId, request.fileId());
        if (request.validFrom() != null && request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "资质有效期结束日期不能早于开始日期");
        }
        InstitutionQualification input = new InstitutionQualification(null, request.institutionId(),
                request.qualificationType(), request.title(), support.encrypt(request.licenseNo()), request.fileId(),
                request.validFrom(), request.validTo(), "PENDING_REVIEW", user.userId(), null, null, null, null);
        repository.insertQualification(input);
        InstitutionQualification created = repository.findLatestQualification(tenantId, request.institutionId(),
                request.fileId(), request.title()).orElseThrow();
        outboxService.append(tenantId, "institution.qualification.created.v1:" + created.id(),
                "institution.qualification.created.v1", "institution", Long.toString(created.institutionId()),
                Map.of("institutionId", created.institutionId(), "qualificationId", created.id()));
        auditService.record(tenantId, user.userId(), "INSTITUTION_QUALIFICATION_CREATE", "INSTITUTION",
                Long.toString(created.institutionId()), null, support.response(created), null);
        support.record(tenantId, QUALIFICATION_SCOPE, key, "INSTITUTION_QUALIFICATION", created.id(), payload);
        return support.response(created);
    }

    private ChangeRequestResponse doReviewQualification(long tenantId, CurrentUser user, long id,
                                                        CreateWorkflowRequest request, String key, String payload) {
        InstitutionQualification qualification = repository.findQualificationForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资质不存在"));
        InstitutionProfile profile = requireVisible(tenantId, user, qualification.institutionId());
        support.requireChange(tenantId, profile, user);
        if (!"PENDING_REVIEW".equals(qualification.status()) || !qualification.version().equals(request.baseVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "资质已审核或版本不一致");
        }
        support.requireClean(tenantId, qualification.fileId());
        support.requireClean(tenantId, request.evidenceFileId());
        String beforeMasked = support.jsonText(Map.of("status", qualification.status()));
        String afterMasked = support.jsonText(Map.of("status", "APPROVED"));
        ProfileChangeRequest draft = new ProfileChangeRequest(null, qualification.institutionId(), "QUALIFICATION_REVIEW",
                null, id, profile.version(), qualification.version(), null, null, beforeMasked, afterMasked,
                request.reason(), request.evidenceFileId(), "PENDING_REVIEW", "MARKET", user.userId(), Instant.now(), null,
                InstitutionChangeService.activeTarget("QUALIFICATION_REVIEW", id, null), null, null);
        ChangeRequestResponse response = changeService.create(tenantId, user, draft);
        support.record(tenantId, QUALIFICATION_REVIEW_SCOPE, key, "INSTITUTION_CHANGE", response.id(), payload);
        return response;
    }

    private InstitutionProfile requireVisible(long tenantId, CurrentUser user, long institutionId) {
        InstitutionProfile profile = repository.findProfile(tenantId, institutionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在或不可见"));
        support.requireVisible(tenantId, profile, user);
        return profile;
    }

    private InstitutionContract replayContract(long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, CONTRACT_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "合同创建冲突且没有已提交幂等结果");
        }
        return contract(tenantId, user, support.resourceId(record));
    }

    private QualificationResponse replayQualification(long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, QUALIFICATION_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "资质创建冲突且没有已提交幂等结果");
        }
        return qualification(tenantId, user, support.resourceId(record));
    }

    private ChangeRequestResponse replayChange(long tenantId, CurrentUser user, String scope,
                                               String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, scope, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档审核申请冲突且没有已提交幂等结果");
        }
        return changeService.detail(tenantId, user, support.resourceId(record));
    }

    private record WorkflowCanonical(long id, long baseVersion, String reason, Long evidenceFileId) {
    }
}
