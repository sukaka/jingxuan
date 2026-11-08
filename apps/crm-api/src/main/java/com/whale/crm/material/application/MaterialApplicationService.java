package com.whale.crm.material.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.whale.crm.file.api.dto.DownloadResponse;
import com.whale.crm.file.application.FileApplicationService;
import com.whale.crm.file.domain.FileClassification;
import com.whale.crm.file.domain.FileMetadata;
import com.whale.crm.material.api.dto.CreateMaterialRevisionRequest;
import com.whale.crm.material.api.dto.CreateMaterialRequest;
import com.whale.crm.material.api.dto.MaterialDownloadResponse;
import com.whale.crm.material.api.dto.MaterialResponse;
import com.whale.crm.material.api.dto.MaterialRevisionResponse;
import com.whale.crm.material.api.dto.OfflineMaterialRequest;
import com.whale.crm.material.api.dto.ReviewMaterialRevisionRequest;
import com.whale.crm.material.api.dto.SubmitMaterialRevisionRequest;
import com.whale.crm.material.api.dto.UpdateMaterialRequest;
import com.whale.crm.material.domain.Material;
import com.whale.crm.material.domain.MaterialListRow;
import com.whale.crm.material.domain.MaterialRepository;
import com.whale.crm.material.domain.MaterialRevision;
import com.whale.crm.material.domain.MaterialScopeQuery;
import com.whale.crm.material.domain.MaterialSearchCriteria;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyRecord;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MaterialApplicationService {
    private static final Set<String> STATUSES = Set.of("DRAFT", "PENDING", "APPROVED", "REJECTED", "OFFLINE");
    private static final String OFFLINE_SCOPE = "material.offline";

    private final MaterialRepository repository;
    private final MaterialScopeService scopeService;
    private final FileApplicationService fileService;
    private final AuditService auditService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public MaterialApplicationService(MaterialRepository repository, MaterialScopeService scopeService,
                                      FileApplicationService fileService, AuditService auditService,
                                      IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.scopeService = scopeService;
        this.fileService = fileService;
        this.auditService = auditService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<MaterialResponse> search(long tenantId, CurrentUser user, MaterialSearchCriteria criteria) {
        scopeService.requireRead(user);
        MaterialSearchCriteria normalized = normalizeCriteria(criteria);
        MaterialScopeQuery scope = scopeService.query(user);
        int offset = Math.toIntExact((long) (normalized.page() - 1) * normalized.size());
        List<MaterialResponse> items = repository.search(tenantId, scope, normalized, offset, normalized.size())
                .stream().map(this::toResponse).toList();
        return new PageResponse<>(items, repository.count(tenantId, scope, normalized),
                normalized.page(), normalized.size());
    }

    @Transactional(readOnly = true)
    public MaterialResponse detail(long tenantId, CurrentUser user, long materialId) {
        scopeService.requireRead(user);
        return detailInternal(tenantId, materialId, scopeService.query(user));
    }

    @Transactional
    public MaterialResponse create(long tenantId, CurrentUser user, CreateMaterialRequest request) {
        Input input = input(request.title(), request.categoryCode(), request.institutionId(), request.projectId(),
                request.cityCode(), request.summary(), request.tags(), request.fileId(), request.changeNote());
        scopeService.requireWrite(user, input.institutionId());
        requireClean(tenantId, user, input.fileId(), input.institutionId());
        MaterialScopeQuery scope = scopeService.query(user);
        String materialNo = "MT" + UlidCreator.getUlid();
        Material draft = new Material(null, tenantId, materialNo, input.title(), input.categoryCode(),
                input.institutionId(), input.projectId(), input.cityCode(), input.summary(), input.tagsJson(),
                null, "DRAFT", null, null, null, null, user.userId(), null, null, 0L);
        repository.insertMaterial(draft);
        Material material = repository.findInsertedMaterial(tenantId, materialNo, user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "素材创建失败"));
        MaterialRevision revision = newRevision(material.id(), tenantId, 1, input, user.userId());
        repository.insertRevision(revision);
        MaterialRevision created = repository.findInsertedRevision(tenantId, material.id(), 1, user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "素材修订创建失败"));
        auditService.record(tenantId, user.userId(), "MATERIAL_CREATE", "MATERIAL", material.id().toString(),
                null, auditSnapshot(material.id(), created), created.changeNote());
        return detailInternal(tenantId, material.id(), scope);
    }

    @Transactional
    public MaterialResponse update(long tenantId, CurrentUser user, long materialId, UpdateMaterialRequest request) {
        MaterialScopeQuery scope = scopeService.query(user);
        Material material = lockMaterial(tenantId, materialId, scope);
        scopeService.requireWrite(user, material.institutionId());
        requireActive(material);
        MaterialRevision revision = lockRevision(tenantId, materialId, request.revisionId(), scope);
        scopeService.requireEditable(scope, revision);
        requireVersion(revision.version(), request.version());
        requireStatus(material, revision, "DRAFT", "只有草稿修订可以编辑");
        Input input = input(request.title(), request.categoryCode(), request.institutionId(), request.projectId(),
                request.cityCode(), request.summary(), request.tags(), request.fileId(), request.changeNote());
        requireSameInstitution(material, input.institutionId());
        requireClean(tenantId, user, input.fileId(), input.institutionId());
        MaterialRevision updated = new MaterialRevision(revision.id(), tenantId, materialId, revision.revisionNo(),
                input.title(), input.summary(), input.categoryCode(), input.institutionId(), input.projectId(),
                input.cityCode(), input.tagsJson(), input.fileId(), input.changeNote(), "DRAFT", null, null,
                null, revision.createdBy(), revision.createdAt(), null, revision.version());
        if (repository.updateDraft(updated, scope) != 1
                || repository.updateMaterialContent(tenantId, materialId, material.version(), updated, scope) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已变化，请刷新后重试");
        }
        auditService.record(tenantId, user.userId(), "MATERIAL_UPDATE", "MATERIAL", Long.toString(materialId),
                auditSnapshot(materialId, revision), auditSnapshot(materialId, updated), updated.changeNote());
        return detailInternal(tenantId, materialId, scope);
    }

    @Transactional
    public MaterialResponse createRevision(long tenantId, CurrentUser user, long materialId,
                                           CreateMaterialRevisionRequest request) {
        MaterialScopeQuery scope = scopeService.query(user);
        Material material = lockMaterial(tenantId, materialId, scope);
        scopeService.requireWrite(user, material.institutionId());
        requireActive(material);
        requireVersion(material.version(), request.version());
        MaterialRevision latest = repository.findLatestScopedRevisionForUpdate(tenantId, materialId, scope)
                .orElseThrow(this::notFound);
        scopeService.requireEditable(scope, latest);
        if (!Set.of("APPROVED", "REJECTED").contains(latest.status())
                || !latest.status().equals(material.reviewStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "当前仍有草稿或待审修订，不能新增修订");
        }
        Input input = input(request.title(), request.categoryCode(), request.institutionId(), request.projectId(),
                request.cityCode(), request.summary(), request.tags(), request.fileId(), request.changeNote());
        requireSameInstitution(material, input.institutionId());
        requireClean(tenantId, user, input.fileId(), input.institutionId());
        MaterialRevision revision = newRevision(materialId, tenantId, latest.revisionNo() + 1, input, user.userId());
        repository.insertRevision(revision);
        MaterialRevision created = repository.findInsertedRevision(tenantId, materialId, revision.revisionNo(),
                user.userId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "素材修订创建失败"));
        if (repository.updateMaterialContent(tenantId, materialId, material.version(), created, scope) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已变化，请刷新后重试");
        }
        auditService.record(tenantId, user.userId(), "MATERIAL_REVISION_CREATE", "MATERIAL",
                Long.toString(materialId), auditSnapshot(materialId, latest), auditSnapshot(materialId, created),
                created.changeNote());
        return detailInternal(tenantId, materialId, scope);
    }

    @Transactional
    public MaterialResponse submit(long tenantId, CurrentUser user, long materialId, long revisionId,
                                   SubmitMaterialRevisionRequest request) {
        MaterialScopeQuery scope = scopeService.query(user);
        Material material = lockMaterial(tenantId, materialId, scope);
        scopeService.requireWrite(user, material.institutionId());
        requireActive(material);
        MaterialRevision revision = lockRevision(tenantId, materialId, revisionId, scope);
        scopeService.requireEditable(scope, revision);
        requireVersion(revision.version(), request.version());
        requireStatus(material, revision, "DRAFT", "只有草稿修订可以送审");
        requireClean(tenantId, user, revision.fileId(), revision.institutionId());
        if (repository.submitRevision(tenantId, revisionId, materialId, revision.version(), scope) != 1
                || repository.submitMaterial(tenantId, materialId, material.version(), scope) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已变化或已送审");
        }
        auditService.record(tenantId, user.userId(), "MATERIAL_SUBMIT", "MATERIAL", Long.toString(materialId),
                auditSnapshot(materialId, revision), Map.of("materialId", materialId, "revisionId", revisionId,
                        "status", "PENDING", "fileId", revision.fileId()), null);
        return detailInternal(tenantId, materialId, scope);
    }

    @Transactional
    public MaterialResponse review(long tenantId, CurrentUser user, long materialId, long revisionId,
                                   ReviewMaterialRevisionRequest request) {
        if (!Set.of("APPROVED", "REJECTED").contains(request.decision())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "审核决定只能是APPROVED或REJECTED");
        }
        String reviewNote = normalized(request.reviewNote());
        if ("REJECTED".equals(request.decision()) && reviewNote == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "拒绝素材必须填写审核意见");
        }
        MaterialScopeQuery scope = scopeService.query(user);
        Material material = lockMaterial(tenantId, materialId, scope);
        scopeService.requireReview(user, material.institutionId());
        requireActive(material);
        MaterialRevision revision = lockRevision(tenantId, materialId, revisionId, scope);
        requireVersion(revision.version(), request.version());
        requireStatus(material, revision, "PENDING", "只有待审修订可以审核");
        if (revision.createdBy() == user.userId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "申请人不能自审素材修订");
        }
        requireClean(tenantId, user, revision.fileId(), revision.institutionId());
        Instant reviewedAt = Instant.now();
        Long approvedRevisionId = "APPROVED".equals(request.decision()) ? revisionId : null;
        if (repository.reviewRevision(tenantId, revisionId, materialId, revision.version(), request.decision(),
                user.userId(), reviewNote, reviewedAt, scope) != 1
                || repository.reviewMaterial(tenantId, materialId, material.version(), request.decision(),
                user.userId(), approvedRevisionId, scope) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已审核或版本不一致");
        }
        auditService.record(tenantId, user.userId(), "MATERIAL_REVIEW", "MATERIAL", Long.toString(materialId),
                auditSnapshot(materialId, revision), Map.of("materialId", materialId, "revisionId", revisionId,
                        "status", request.decision(), "fileId", revision.fileId()), reviewNote);
        return detailInternal(tenantId, materialId, scope);
    }

    @Transactional
    public MaterialResponse offline(long tenantId, CurrentUser user, long materialId,
                                    OfflineMaterialRequest request, String idempotencyKey) {
        String key = requireKey(idempotencyKey);
        String reason = normalized(request.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "下架原因不能为空");
        }
        String payload = canonical(new OfflineCanonical(materialId, request.version(), reason));
        MaterialScopeQuery scope = scopeService.query(user);
        Material material = lockMaterial(tenantId, materialId, scope);
        scopeService.requireWrite(user, material.institutionId());
        IdempotencyRecord replay = idempotencyService.lookup(tenantId, OFFLINE_SCOPE, key, payload).orElse(null);
        if (replay != null) {
            requireReplayResource(replay, materialId);
            return detailInternal(tenantId, materialId, scope);
        }
        if (material.offlineAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已下架，不能重复下架");
        }
        if (material.currentRevisionId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有存在已审核版本的素材可以下架");
        }
        requireVersion(material.version(), request.version());
        Instant offlineAt = Instant.now();
        if (repository.offlineMaterial(tenantId, materialId, material.version(), user.userId(), reason,
                offlineAt, scope) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已变化或没有可下架的已审核版本");
        }
        auditService.record(tenantId, user.userId(), "MATERIAL_OFFLINE", "MATERIAL", Long.toString(materialId),
                Map.of("materialId", materialId, "lifecycleStatus", "ACTIVE", "reviewStatus", material.reviewStatus(),
                        "currentRevisionId", material.currentRevisionId()),
                Map.of("materialId", materialId, "lifecycleStatus", "OFFLINE", "reviewStatus", material.reviewStatus(),
                        "currentRevisionId", material.currentRevisionId(), "offlineBy", user.userId(),
                        "offlineAt", offlineAt), reason);
        idempotencyService.recordSuccess(tenantId, OFFLINE_SCOPE, key, "MATERIAL", Long.toString(materialId), payload);
        return detailInternal(tenantId, materialId, scope);
    }

    @Transactional(readOnly = true)
    public MaterialRevision getApprovedRevisionForKnowledge(long tenantId, long materialId, long revisionId) {
        return approvedRevisionForKnowledge(tenantId, materialId, revisionId,
                new MaterialScopeQuery("ALL", List.of(), 0L, false, false, false));
    }

    @Transactional(readOnly = true)
    public MaterialRevision getApprovedRevisionForKnowledge(long tenantId, CurrentUser user, long materialId, long revisionId) {
        if (!user.permissions().contains("ai:knowledge:publish")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "没有知识资料发布权限");
        }
        return approvedRevisionForKnowledge(tenantId, materialId, revisionId, scopeService.query(user));
    }

    private MaterialRevision approvedRevisionForKnowledge(long tenantId, long materialId, long revisionId,
                                                           MaterialScopeQuery scope) {
        Material material = repository.findScopedMaterial(tenantId, materialId, scope).orElseThrow(this::notFound);
        if (material.offlineAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "已下架素材不能发布到知识库");
        }
        MaterialRevision revision = repository.findScopedRevision(tenantId, materialId, revisionId, scope)
                .orElseThrow(this::notFound);
        if (!"APPROVED".equals(revision.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有 APPROVED 素材修订可以发布到知识库");
        }
        return revision;
    }

    public MaterialDownloadResponse download(long tenantId, CurrentUser user, long materialId, long revisionId) {
        scopeService.requireRead(user);
        MaterialScopeQuery scope = scopeService.query(user);
        MaterialRevision revision = repository.findScopedRevision(tenantId, materialId, revisionId, scope)
                .orElseThrow(this::notFound);
        scopeService.requireDownload(scope, revision);
        requireClean(tenantId, user, revision.fileId(), revision.institutionId());
        DownloadResponse download = fileService.download(tenantId, user, revision.fileId());
        auditService.record(tenantId, user.userId(), "MATERIAL_DOWNLOAD", "MATERIAL_REVISION",
                Long.toString(revisionId), null, Map.of("materialId", materialId, "revisionId", revisionId,
                        "fileId", revision.fileId(), "status", revision.status()), revision.status());
        return new MaterialDownloadResponse(materialId, revisionId, revision.fileId(), download.downloadUrl(),
                download.expiresAt());
    }

    private MaterialRevision newRevision(long materialId, long tenantId, int revisionNo, Input input, long createdBy) {
        return new MaterialRevision(null, tenantId, materialId, revisionNo, input.title(), input.summary(),
                input.categoryCode(), input.institutionId(), input.projectId(), input.cityCode(), input.tagsJson(),
                input.fileId(), input.changeNote(), "DRAFT", null, null, null, createdBy, null, null, 0L);
    }

    private Material lockMaterial(long tenantId, long materialId, MaterialScopeQuery scope) {
        return repository.findScopedMaterialForUpdate(tenantId, materialId, scope).orElseThrow(this::notFound);
    }

    private MaterialRevision lockRevision(long tenantId, long materialId, long revisionId, MaterialScopeQuery scope) {
        return repository.findScopedRevisionForUpdate(tenantId, materialId, revisionId, scope)
                .orElseThrow(this::notFound);
    }

    private MaterialResponse detailInternal(long tenantId, long materialId, MaterialScopeQuery scope) {
        Material material = repository.findScopedMaterial(tenantId, materialId, scope).orElseThrow(this::notFound);
        List<MaterialRevision> revisions = repository.findScopedRevisions(tenantId, materialId, scope).stream()
                .sorted(Comparator.comparing(MaterialRevision::revisionNo).reversed()).toList();
        if (revisions.isEmpty()) {
            throw notFound();
        }
        return toResponse(material, revisions);
    }

    private MaterialResponse toResponse(MaterialListRow row) {
        return toResponse(row.material(), List.of(row.revision()));
    }

    private MaterialResponse toResponse(Material material, List<MaterialRevision> revisions) {
        MaterialRevision displayed = revisions.getFirst();
        List<MaterialRevisionResponse> revisionResponses = revisions.stream()
                .map(revision -> MaterialRevisionResponse.from(revision, tags(revision.tagsJson()))).toList();
        return MaterialResponse.from(material, displayed, tags(displayed.tagsJson()), revisionResponses);
    }

    private void requireClean(long tenantId, CurrentUser user, long fileId, Long institutionId) {
        FileMetadata metadata = fileService.requireCleanUsable(tenantId, user, fileId,
                FileClassification.MATERIAL, institutionId);
        if (!Objects.equals(metadata.institutionId(), institutionId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "文件机构归属与素材不一致");
        }
    }

    private void requireSameInstitution(Material material, Long institutionId) {
        if (!Objects.equals(material.institutionId(), institutionId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "机构归属不可在修订中变更");
        }
    }

    private void requireStatus(Material material, MaterialRevision revision, String expected, String message) {
        if (!expected.equals(material.reviewStatus()) || !expected.equals(revision.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private void requireActive(Material material) {
        if (material.offlineAt() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材已下架，仅可查看历史版本");
        }
    }

    private void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new BusinessException(ErrorCode.CONFLICT, "素材版本不一致，请刷新后重试");
        }
    }

    private String requireKey(String key) {
        if (key == null || key.isBlank() || key.strip().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "下架请求必须提供1至128字符的幂等键");
        }
        return key.strip();
    }

    private String canonical(OfflineCanonical value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("素材下架请求规范化失败", ex);
        }
    }

    private void requireReplayResource(IdempotencyRecord record, long materialId) {
        if (!"MATERIAL".equals(record.getResourceType())
                || !Long.toString(materialId).equals(record.getResourceId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等结果不属于该素材");
        }
    }

    private MaterialSearchCriteria normalizeCriteria(MaterialSearchCriteria criteria) {
        if (criteria.page() < 1 || criteria.size() < 1 || criteria.size() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分页参数不合法");
        }
        String status = normalized(criteria.status());
        if (status != null && !STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材状态不合法");
        }
        return new MaterialSearchCriteria(normalized(criteria.keyword()), normalized(criteria.categoryCode()),
                criteria.institutionId(), criteria.projectId(), normalized(criteria.cityCode()), status,
                criteria.globalOnly(), criteria.page(), criteria.size());
    }

    private Input input(String title, String categoryCode, Long institutionId, Long projectId, String cityCode,
                        String summary, List<String> tags, Long fileId, String changeNote) {
        String normalizedTitle = normalized(title);
        String normalizedCategory = normalized(categoryCode);
        if (normalizedTitle == null || normalizedCategory == null || fileId == null || fileId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材标题、分类和文件不能为空");
        }
        return new Input(normalizedTitle, normalizedCategory, institutionId, projectId, normalized(cityCode),
                normalized(summary), tagsJson(tags), fileId, normalized(changeNote));
    }

    private String tagsJson(List<String> tags) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (tags != null) {
            if (tags.size() > 20) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材标签不能超过20个");
            }
            for (String tag : tags) {
                String value = normalized(tag);
                if (value == null || value.length() > 40) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材标签不能为空且长度不能超过40");
                }
                normalized.add(value);
            }
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "素材标签格式不合法");
        }
    }

    private List<String> tags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("素材标签数据格式错误", ex);
        }
    }

    private String normalized(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<String, Object> auditSnapshot(long materialId, MaterialRevision revision) {
        return Map.of("materialId", materialId, "revisionId", revision.id(), "status", revision.status(),
                "fileId", revision.fileId());
    }

    private BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "素材不存在或不在数据范围内");
    }

    private record Input(String title, String categoryCode, Long institutionId, Long projectId, String cityCode,
                         String summary, String tagsJson, Long fileId, String changeNote) {
    }

    private record OfflineCanonical(long materialId, long version, String reason) {
    }
}
