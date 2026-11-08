package com.whale.crm.institution.application;

import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.institution.api.dto.CreateInstitutionMerchantResourceRequest;
import com.whale.crm.institution.api.dto.UpdateInstitutionMerchantResourceRequest;
import com.whale.crm.institution.domain.InstitutionMerchantResource;
import com.whale.crm.institution.domain.InstitutionMerchantResourceRepository;
import com.whale.crm.institution.domain.InstitutionMerchantResourceSummary;
import com.whale.crm.institution.domain.InstitutionProfile;
import com.whale.crm.institution.domain.InstitutionRepository;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class InstitutionMerchantResourceService {
    private static final String CREATE_SCOPE = "institution.merchant-resource.create";
    private static final String UPDATE_SCOPE = "institution.merchant-resource.update";
    private static final Set<String> RESOURCE_TYPES = Set.of("DOCTOR", "EQUIPMENT", "CASE");
    private static final Set<String> RESOURCE_STATUSES = Set.of("PUBLISHED", "OFFLINE");

    private final InstitutionMerchantResourceRepository repository;
    private final InstitutionRepository institutionRepository;
    private final InstitutionSupport support;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public InstitutionMerchantResourceService(InstitutionMerchantResourceRepository repository,
                                               InstitutionRepository institutionRepository,
                                               InstitutionSupport support, AuditService auditService,
                                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.support = support;
        this.auditService = auditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<InstitutionMerchantResource> search(long tenantId, CurrentUser user, Long institutionId,
                                                              String resourceType, String status, String keyword,
                                                              int page, int size) {
        if (institutionId != null) {
            requireVisible(tenantId, user, institutionId);
        }
        InstitutionScope scope = support.scope(tenantId, user);
        if ("NONE".equals(scope.scopeType())) {
            return new PageResponse<>(List.of(), 0, page, size);
        }
        String type = enumFilter(resourceType, RESOURCE_TYPES, "资料类型");
        String actualStatus = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : enumFilter(status, RESOURCE_STATUSES, "资料状态");
        int offset = (page - 1) * size;
        String normalizedKeyword = normalize(keyword);
        return new PageResponse<>(repository.search(tenantId, scope, institutionId, type, actualStatus,
                        normalizedKeyword, offset, size),
                repository.count(tenantId, scope, institutionId, type, actualStatus, normalizedKeyword), page, size);
    }

    @Transactional(readOnly = true)
    public List<InstitutionMerchantResourceSummary> summary(long tenantId, CurrentUser user) {
        InstitutionScope scope = support.scope(tenantId, user);
        return "NONE".equals(scope.scopeType()) ? List.of() : repository.summarize(tenantId, scope);
    }

    public InstitutionMerchantResource create(long tenantId, CurrentUser user, long institutionId,
                                               CreateInstitutionMerchantResourceRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new CreateCanonical(institutionId, request));
        IdempotencyRecord replay = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (replay != null) {
            return resource(tenantId, user, support.resourceId(replay));
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    doCreate(tenantId, user, institutionId, request, key, payload)));
        } catch (DuplicateKeyException ex) {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    replayCreate(tenantId, user, key, payload)));
        }
    }

    public InstitutionMerchantResource update(long tenantId, CurrentUser user, long id,
                                               UpdateInstitutionMerchantResourceRequest request, String idempotencyKey) {
        String key = support.requireKey(idempotencyKey);
        String payload = support.jsonText(new UpdateCanonical(id, request));
        IdempotencyRecord replay = support.lookup(tenantId, UPDATE_SCOPE, key, payload);
        if (replay != null) {
            return resource(tenantId, user, id);
        }
        try {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    doUpdate(tenantId, user, id, request, key, payload)));
        } catch (DuplicateKeyException ex) {
            return Objects.requireNonNull(transactionTemplate.execute(status ->
                    replayUpdate(tenantId, user, id, key, payload)));
        }
    }

    private InstitutionMerchantResource doCreate(long tenantId, CurrentUser user, long institutionId,
                                                  CreateInstitutionMerchantResourceRequest request,
                                                  String key, String payload) {
        support.requireEmployeeWrite(tenantId, user);
        requireVisible(tenantId, user, institutionId);
        if (support.lookup(tenantId, CREATE_SCOPE, key, payload) != null) {
            return replayCreate(tenantId, user, key, payload);
        }
        support.requireMerchantResourceClean(tenantId, request.fileId(), institutionId);
        InstitutionMerchantResource input = new InstitutionMerchantResource(null, UlidCreator.getUlid().toString(),
                institutionId, request.resourceType(), normalize(request.title()), normalize(request.subtitle()),
                normalize(request.description()), request.fileId(), "PUBLISHED", user.userId(), null, null, null, null);
        repository.insert(input);
        InstitutionMerchantResource created = repository.findByResourceNo(tenantId, input.resourceNo()).orElseThrow();
        auditService.record(tenantId, user.userId(), "INSTITUTION_MERCHANT_RESOURCE_CREATE",
                "INSTITUTION_MERCHANT_RESOURCE", Long.toString(created.id()), null, created, null);
        support.record(tenantId, CREATE_SCOPE, key, "INSTITUTION_MERCHANT_RESOURCE", created.id(), payload);
        return created;
    }

    private InstitutionMerchantResource doUpdate(long tenantId, CurrentUser user, long id,
                                                  UpdateInstitutionMerchantResourceRequest request,
                                                  String key, String payload) {
        InstitutionMerchantResource before = repository.findForUpdate(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构资料不存在"));
        support.requireEmployeeWrite(tenantId, user);
        requireVisible(tenantId, user, before.institutionId());
        if (support.lookup(tenantId, UPDATE_SCOPE, key, payload) != null) {
            return before;
        }
        if (!before.version().equals(request.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "资料已被他人修改，请刷新后重试");
        }
        support.requireMerchantResourceClean(tenantId, request.fileId(), before.institutionId());
        int updated = repository.update(tenantId, id, request.version(), normalize(request.title()),
                normalize(request.subtitle()), normalize(request.description()), request.fileId(),
                request.status(), user.userId());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "资料已被他人修改，请刷新后重试");
        }
        InstitutionMerchantResource after = repository.find(tenantId, id).orElseThrow();
        auditService.record(tenantId, user.userId(), "INSTITUTION_MERCHANT_RESOURCE_UPDATE",
                "INSTITUTION_MERCHANT_RESOURCE", Long.toString(id), before, after, null);
        support.record(tenantId, UPDATE_SCOPE, key, "INSTITUTION_MERCHANT_RESOURCE", id, payload);
        return after;
    }

    private InstitutionMerchantResource resource(long tenantId, CurrentUser user, long id) {
        InstitutionMerchantResource resource = repository.find(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构资料不存在或不可见"));
        requireVisible(tenantId, user, resource.institutionId());
        return resource;
    }

    private InstitutionProfile requireVisible(long tenantId, CurrentUser user, long institutionId) {
        InstitutionProfile profile = institutionRepository.findProfile(tenantId, institutionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "机构不存在或不可见"));
        support.requireVisible(tenantId, profile, user);
        return profile;
    }

    private InstitutionMerchantResource replayCreate(long tenantId, CurrentUser user, String key, String payload) {
        IdempotencyRecord record = support.lookup(tenantId, CREATE_SCOPE, key, payload);
        if (record == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "资料创建冲突且没有已提交幂等结果");
        }
        return resource(tenantId, user, support.resourceId(record));
    }

    private InstitutionMerchantResource replayUpdate(long tenantId, CurrentUser user, long id,
                                                      String key, String payload) {
        if (support.lookup(tenantId, UPDATE_SCOPE, key, payload) == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "资料更新冲突且没有已提交幂等结果");
        }
        return resource(tenantId, user, id);
    }

    private String enumFilter(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不受支持");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? null : value.strip();
    }

    private record CreateCanonical(long institutionId, CreateInstitutionMerchantResourceRequest request) {
    }

    private record UpdateCanonical(long id, UpdateInstitutionMerchantResourceRequest request) {
    }
}
