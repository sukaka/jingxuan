package com.whale.crm.product.application;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.ApiResponse;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import com.whale.crm.platform.web.PageResponse;
import com.whale.crm.product.api.dto.CreatePackageRequest;
import com.whale.crm.product.api.dto.CreatePackageVersionRequest;
import com.whale.crm.product.api.dto.CreateProjectRequest;
import com.whale.crm.product.api.dto.CreateSkuRequest;
import com.whale.crm.product.api.dto.PackageProjectItemRequest;
import com.whale.crm.product.api.dto.PackageSelectionGroupRequest;
import com.whale.crm.product.api.dto.ReplaceVersionProjectsRequest;
import com.whale.crm.product.api.dto.UpdatePackageRequest;
import com.whale.crm.product.api.dto.UpdatePackageVersionRequest;
import com.whale.crm.product.api.dto.UpdateProjectRequest;
import com.whale.crm.product.api.dto.UpdateSkuRequest;
import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.CatalogRules;
import com.whale.crm.product.domain.Institution;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageSelectionGroup;
import com.whale.crm.product.domain.PackageSelectionRules;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.PackageVersionSnapshot;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class CatalogApplicationService {
    private final CatalogRepository repository;
    private final AuditService auditService;

    public CatalogApplicationService(CatalogRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<Project> projects(long tenantId, String keyword, String status, int page, int size) {
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchProjects(tenantId, keyword, status, offset, size),
                repository.countProjects(tenantId, keyword, status), page, size);
    }

    @Transactional(readOnly = true)
    public Project activeProjectForSharing(long tenantId, long projectId) {
        Project project = requireProject(tenantId, projectId);
        if (!"ACTIVE".equals(project.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在或未启用");
        }
        return project;
    }

    @Transactional
    public Project createProject(long tenantId, CurrentUser user, CreateProjectRequest request) {
        if (repository.findProjectByCode(tenantId, request.projectCode().strip()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目编码已存在");
        }
        Project input = new Project(null, request.projectCode().strip(), request.projectName().strip(),
                blankToNull(request.categoryCode()), request.listPriceFen(), "ACTIVE", null, null);
        try {
            repository.insertProject(tenantId, input);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目编码已存在");
        }
        Project created = repository.findProjectByCode(tenantId, input.projectCode()).orElseThrow();
        audit(tenantId, user, "PROJECT_CREATE", "PROJECT", created.id(), null, created);
        return created;
    }

    @Transactional
    public Project updateProject(long tenantId, CurrentUser user, long id, UpdateProjectRequest request) {
        Project before = requireProject(tenantId, id);
        Project input = new Project(id, before.projectCode(), request.projectName().strip(),
                blankToNull(request.categoryCode()), request.listPriceFen(), request.status(), before.createdAt(), request.version());
        if (repository.updateProject(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目已被他人修改，请刷新后重试");
        }
        Project after = requireProject(tenantId, id);
        audit(tenantId, user, "PROJECT_UPDATE", "PROJECT", id, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductPackage> packages(long tenantId, CurrentUser user, String keyword, String status,
                                                 int page, int size) {
        Set<Long> scope = institutionScope(user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchPackages(tenantId, keyword, status, scope, offset, size),
                repository.countPackages(tenantId, keyword, status, scope), page, size);
    }

    @Transactional
    public ProductPackage createPackage(long tenantId, CurrentUser user, CreatePackageRequest request) {
        if (repository.findPackageByCode(tenantId, request.packageCode().strip()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐编码已存在");
        }
        ProductPackage input = new ProductPackage(null, request.packageCode().strip(), request.packageName().strip(),
                "ACTIVE", null, null);
        try {
            repository.insertPackage(tenantId, input);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐编码已存在");
        }
        ProductPackage created = repository.findPackageByCode(tenantId, input.packageCode()).orElseThrow();
        audit(tenantId, user, "PACKAGE_CREATE", "PACKAGE", created.id(), null, created);
        return created;
    }

    @Transactional
    public ProductPackage updatePackage(long tenantId, CurrentUser user, long id, UpdatePackageRequest request) {
        ProductPackage before = requirePackage(tenantId, id);
        ProductPackage input = new ProductPackage(id, before.packageCode(), request.packageName().strip(),
                request.status(), before.createdAt(), request.version());
        if (repository.updatePackage(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐已被他人修改，请刷新后重试");
        }
        if ("INACTIVE".equals(request.status())) {
            repository.disableSkusByPackage(tenantId, id, Instant.now());
        }
        ProductPackage after = requirePackage(tenantId, id);
        audit(tenantId, user, "PACKAGE_UPDATE", "PACKAGE", id, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<PackageVersion> versions(long tenantId, CurrentUser user, long packageId) {
        requirePackage(tenantId, packageId);
        Set<Long> scope = institutionScope(user);
        if (scope == null) {
            return repository.findVersions(tenantId, packageId);
        }
        if (scope.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "套餐不存在或不适用于本机构");
        }
        List<PackageVersion> result = repository.findVersionsByInstitutionIds(tenantId, packageId, scope);
        if (result.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "套餐不存在或不适用于本机构");
        }
        return result;
    }

    @Transactional
    public PackageVersionSnapshot createVersion(long tenantId, CurrentUser user, long packageId,
                                                CreatePackageVersionRequest request) {
        repository.findPackageForUpdate(tenantId, packageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "套餐不存在"));
        List<PackageProject> items = validateProjects(tenantId, request.projects());
        if (!"CHOOSE_N".equals(request.selectionMode())) {
            validateDistinctProjects(items);
        }
        List<PackageSelectionGroup> groups = validateSelectionGroups(request.selectionMode(), request.choiceCount(),
                items, request.groups());
        int versionNo = repository.nextVersionNo(tenantId, packageId);
        PackageVersion input = new PackageVersion(null, packageId, versionNo, request.selectionMode(),
                request.choiceCount(), blankToNull(request.selectionRules()), "DRAFT", null, null);
        PackageVersion created;
        try {
            repository.insertVersion(tenantId, input);
            created = repository.findVersions(tenantId, packageId).stream()
                    .filter(item -> item.versionNo().equals(versionNo)).findFirst().orElseThrow();
            for (PackageSelectionGroup group : groups) {
                repository.insertSelectionGroup(tenantId, new PackageSelectionGroup(null, created.id(), group.groupCode(),
                        group.groupName(), group.minSelections(), group.maxSelections(), group.sortOrder(), null));
            }
            for (PackageProject item : items) {
                repository.insertVersionProject(tenantId, new PackageProject(null, created.id(), item.projectId(),
                        null, null, null, null, null, item.grantCount(), item.selectionWeight(),
                        item.allocationPriceFen(), item.optionGroup(), item.referencePriceFen(), null));
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本或项目配置重复");
        }
        audit(tenantId, user, "PACKAGE_VERSION_CREATE", "PACKAGE_VERSION", created.id(), null, created);
        return snapshot(tenantId, user, created.id());
    }

    @Transactional
    public PackageVersionSnapshot updateVersion(long tenantId, CurrentUser user, long id,
                                                UpdatePackageVersionRequest request) {
        PackageVersion before = requireVersion(tenantId, id);
        requireDraft(before);
        validateSelection(request.selectionMode(), request.choiceCount());
        List<PackageProject> projects = repository.findVersionProjects(tenantId, id);
        for (PackageProject project : projects) {
            if (!"ACTIVE".equals(project.projectStatus())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "子项目已停用: " + project.projectName());
            }
        }
        if (!"CHOOSE_N".equals(request.selectionMode())) {
            validateDistinctProjects(projects);
        } else {
            List<PackageSelectionGroup> groups = repository.findSelectionGroups(tenantId, id);
            if (!groups.isEmpty()) {
                PackageSelectionRules.validateConfiguration(groups, projects);
            }
        }
        PackageVersion input = new PackageVersion(id, before.packageId(), before.versionNo(), request.selectionMode(),
                request.choiceCount(), blankToNull(request.selectionRules()), before.status(), before.createdAt(), request.version());
        if (repository.updateVersionDraft(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本已被修改或已启用，不能直接覆盖");
        }
        if (!"CHOOSE_N".equals(request.selectionMode())) {
            repository.deleteSelectionGroups(tenantId, id);
        }
        PackageVersion after = requireVersion(tenantId, id);
        audit(tenantId, user, "PACKAGE_VERSION_UPDATE", "PACKAGE_VERSION", id, before, after);
        return snapshot(tenantId, user, id);
    }

    @Transactional
    public PackageVersionSnapshot replaceProjects(long tenantId, CurrentUser user, long id,
                                                  ReplaceVersionProjectsRequest request) {
        PackageVersion before = requireVersion(tenantId, id);
        requireDraft(before);
        List<PackageProject> items = validateProjects(tenantId, request.projects());
        if (!"CHOOSE_N".equals(before.selectionMode())) {
            validateDistinctProjects(items);
        }
        List<PackageSelectionGroup> groups = request.groups() == null
                ? repository.findSelectionGroups(tenantId, id)
                : validateSelectionGroups(before.selectionMode(), before.choiceCount(), items, request.groups());
        if (request.groups() == null) {
            validateSelection(before.selectionMode(), before.choiceCount());
            if (!groups.isEmpty()) {
                if (!"CHOOSE_N".equals(before.selectionMode())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅自选项目套餐支持配置选项组");
                }
                PackageSelectionRules.validateConfiguration(groups, items);
            }
        }
        if (repository.updateVersionStatus(tenantId, id, request.version(), "DRAFT", "DRAFT") == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本已被修改或已激活，不能覆盖项目配置");
        }
        try {
            repository.deleteVersionProjects(tenantId, id);
            repository.deleteSelectionGroups(tenantId, id);
            for (PackageSelectionGroup group : groups) {
                repository.insertSelectionGroup(tenantId, new PackageSelectionGroup(null, id, group.groupCode(), group.groupName(),
                        group.minSelections(), group.maxSelections(), group.sortOrder(), null));
            }
            for (PackageProject item : items) {
                repository.insertVersionProject(tenantId, new PackageProject(null, id, item.projectId(), null, null, null,
                        null, null, item.grantCount(), item.selectionWeight(), item.allocationPriceFen(),
                        item.optionGroup(), item.referencePriceFen(), null));
            }
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "选项组或同一组中的项目配置重复");
        }
        PackageVersionSnapshot after = snapshot(tenantId, user, id);
        audit(tenantId, user, "PACKAGE_VERSION_PROJECTS_UPDATE", "PACKAGE_VERSION", id, before, after);
        return after;
    }

    @Transactional
    public PackageVersionSnapshot activateVersion(long tenantId, CurrentUser user, long id, long version) {
        PackageVersion before = requireVersion(tenantId, id);
        requireDraft(before);
        List<PackageProject> projects = repository.findVersionProjects(tenantId, id);
        List<PackageSelectionGroup> groups = repository.findSelectionGroups(tenantId, id);
        if ("USE_N".equals(before.selectionMode())) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                    "任选次数套餐规则尚未确认，仅可保存为历史模板，暂不支持启用或发放权益",
                    List.of(new ApiResponse.ErrorDetail("Q01", "任选次数共享池规则待运营确认")));
        }
        if (!CatalogRules.canActivate(before, projects, groups)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "激活前必须配置有效子项目、次数和选择模式");
        }
        if (repository.updateVersionStatus(tenantId, id, version, "DRAFT", "ACTIVE") == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本状态已变化，请刷新后重试");
        }
        PackageVersionSnapshot after = snapshot(tenantId, user, id);
        audit(tenantId, user, "PACKAGE_VERSION_ACTIVATE", "PACKAGE_VERSION", id, before, after);
        return after;
    }

    @Transactional
    public PackageVersionSnapshot archiveVersion(long tenantId, CurrentUser user, long id, long version) {
        PackageVersion before = requireVersion(tenantId, id);
        if (!"ACTIVE".equals(before.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅启用中的版本可归档");
        }
        if (repository.updateVersionStatus(tenantId, id, version, "ACTIVE", "ARCHIVED") == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "版本状态已变化，请刷新后重试");
        }
        repository.disableSkusByVersion(tenantId, id, Instant.now());
        PackageVersionSnapshot after = snapshot(tenantId, user, id);
        audit(tenantId, user, "PACKAGE_VERSION_ARCHIVE", "PACKAGE_VERSION", id, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public PackageVersionSnapshot snapshot(long tenantId, CurrentUser user, long id) {
        PackageVersion version = requireVersion(tenantId, id);
        return buildSnapshot(tenantId, user, version, null);
    }

    @Transactional(readOnly = true)
    public PackageVersionSnapshot snapshotBySku(long tenantId, CurrentUser user, long skuId) {
        Sku sku = requireSku(tenantId, skuId);
        PackageVersion version = requireVersion(tenantId, sku.packageVersionId());
        return buildSnapshot(tenantId, user, version, sku);
    }

    private PackageVersionSnapshot buildSnapshot(long tenantId, CurrentUser user, PackageVersion version, Sku targetSku) {
        ProductPackage productPackage = requirePackage(tenantId, version.packageId());
        List<PackageProject> projects = repository.findVersionProjects(tenantId, version.id());
        List<PackageSelectionGroup> groups = repository.findSelectionGroups(tenantId, version.id());
        Set<Long> institutionScope = institutionScope(user);
        List<Sku> skus;
        Set<Long> institutionIds = new LinkedHashSet<>();
        if (targetSku != null) {
            skus = List.of(targetSku);
            institutionIds.addAll(repository.findActiveInstitutionIdsBySku(tenantId, targetSku.id()));
            if (institutionScope != null) {
                institutionIds.retainAll(institutionScope);
                if (institutionIds.isEmpty()) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在或不适用于本机构");
                }
            }
        } else {
            skus = repository.findSkusByVersion(tenantId, version.id());
            if (institutionScope != null) {
                skus = skus.stream().filter(sku -> {
                    Set<Long> ids = new HashSet<>(repository.findActiveInstitutionIdsBySku(tenantId, sku.id()));
                    return ids.stream().anyMatch(institutionScope::contains);
                }).toList();
                if (skus.isEmpty()) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "套餐版本不存在或不适用于本机构");
                }
            }
            for (Sku sku : skus) {
                institutionIds.addAll(repository.findActiveInstitutionIdsBySku(tenantId, sku.id()));
            }
            if (institutionScope != null) {
                institutionIds.retainAll(institutionScope);
            }
        }
        List<Institution> institutions = institutionIds.isEmpty() ? List.of()
                : repository.findActiveInstitutionsByIds(tenantId, institutionIds);
        return new PackageVersionSnapshot(version.id(), productPackage.id(), productPackage.packageCode(),
                productPackage.packageName(), version.versionNo(), version.selectionMode(), version.choiceCount(),
                version.selectionRules(), version.status(), version.version(),
                CatalogRules.rulesStatus(version, groups), CatalogRules.allocationStatus(projects),
                CatalogRules.entitlementIssuable(version, projects, groups), projects, skus, institutions, groups);
    }

    @Transactional(readOnly = true)
    public PageResponse<Sku> skus(long tenantId, CurrentUser user, String keyword, String status, Long packageId,
                                  int page, int size) {
        Set<Long> scope = institutionScope(user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchSkus(tenantId, keyword, status, packageId, scope, offset, size),
                repository.countSkus(tenantId, keyword, status, packageId, scope), page, size);
    }

    @Transactional
    public Sku createSku(long tenantId, CurrentUser user, CreateSkuRequest request) {
        requireVersion(tenantId, request.packageVersionId());
        if (repository.findSkuByCode(tenantId, request.skuCode().strip()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 编码已存在");
        }
        Set<Long> institutionIds = validateInstitutions(tenantId, request.institutionIds());
        Sku input = new Sku(null, request.skuCode().strip(), request.packageVersionId(), null, null, null, null,
                request.specName().strip(), request.salePriceFen(), "INACTIVE", null, null, null, null);
        try {
            repository.insertSku(tenantId, input);
            Sku created = repository.findSkuByCode(tenantId, input.skuCode()).orElseThrow();
            for (Long institutionId : institutionIds) {
                repository.insertSkuInstitution(tenantId, created.id(), institutionId);
            }
            audit(tenantId, user, "SKU_CREATE", "SKU", created.id(), null, created);
            return created;
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 编码或机构配置重复");
        }
    }

    @Transactional
    public Sku updateSku(long tenantId, CurrentUser user, long id, UpdateSkuRequest request) {
        Sku before = requireSku(tenantId, id);
        PackageVersion version = requireVersion(tenantId, before.packageVersionId());
        if (!"DRAFT".equals(version.status())) {
            if (!"ACTIVE".equals(version.status()) || !"INACTIVE".equals(before.status())) {
                throw new BusinessException(ErrorCode.CONFLICT, "已上架或已归档 SKU 不可覆盖，请创建新版本");
            }
            if (!before.specName().equals(request.specName().strip())
                    || !before.salePriceFen().equals(request.salePriceFen())) {
                throw new BusinessException(ErrorCode.CONFLICT, "已激活版本的规格与价格不可覆盖，仅允许为未上架 SKU 补配机构");
            }
        }
        Set<Long> institutionIds = validateInstitutions(tenantId, request.institutionIds());
        Sku input = new Sku(id, before.skuCode(), before.packageVersionId(), null, null, null, null,
                request.specName().strip(), request.salePriceFen(), before.status(), before.onSaleAt(), before.offSaleAt(),
                before.createdAt(), request.version());
        if (repository.updateSku(tenantId, input) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 已被他人修改，请刷新后重试");
        }
        repository.deleteSkuInstitutions(tenantId, id);
        for (Long institutionId : institutionIds) {
            repository.insertSkuInstitution(tenantId, id, institutionId);
        }
        Sku after = requireSku(tenantId, id);
        audit(tenantId, user, "SKU_UPDATE", "SKU", id, before, after);
        return after;
    }

    @Transactional
    public Sku activateSku(long tenantId, CurrentUser user, long id, long version) {
        Sku before = requireSku(tenantId, id);
        PackageVersion packageVersion = requireVersion(tenantId, before.packageVersionId());
        ProductPackage productPackage = requirePackage(tenantId, packageVersion.packageId());
        if (!"ACTIVE".equals(productPackage.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "套餐已停用，不能上架 SKU");
        }
        List<PackageProject> projects = repository.findVersionProjects(tenantId, packageVersion.id());
        List<PackageSelectionGroup> groups = repository.findSelectionGroups(tenantId, packageVersion.id());
        if (!CatalogRules.entitlementIssuable(packageVersion, projects, groups)) {
            if ("PENDING_Q01".equals(CatalogRules.rulesStatus(packageVersion, groups))) {
                throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                        "套餐选择规则尚未确认，当前仅可保存模板，暂不支持上架或发放权益",
                        java.util.List.of(new ApiResponse.ErrorDetail("Q01", "选 N 项的组合、重复选择和每项次数仍需运营确认")));
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "版本未激活或权益配置不完整，不能上架 SKU");
        }
        Set<Long> institutionIds = new LinkedHashSet<>(repository.findInstitutionIdsBySku(tenantId, id));
        if (institutionIds.isEmpty() || repository.countActiveInstitutionsByIds(tenantId, institutionIds) != institutionIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "上架前至少配置一家有效合作机构");
        }
        if (repository.updateSkuStatus(tenantId, id, version, "ACTIVE", Instant.now()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 已被他人修改，请刷新后重试");
        }
        Sku after = requireSku(tenantId, id);
        audit(tenantId, user, "SKU_ACTIVATE", "SKU", id, before, after);
        return after;
    }

    @Transactional
    public Sku disableSku(long tenantId, CurrentUser user, long id, long version) {
        Sku before = requireSku(tenantId, id);
        if (repository.updateSkuStatus(tenantId, id, version, "INACTIVE", Instant.now()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "SKU 已被他人修改，请刷新后重试");
        }
        Sku after = requireSku(tenantId, id);
        audit(tenantId, user, "SKU_DISABLE", "SKU", id, before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public List<Institution> skuInstitutions(long tenantId, CurrentUser user, long skuId) {
        Sku sku = requireSku(tenantId, skuId);
        Set<Long> scope = institutionScope(user);
        Set<Long> ids = new LinkedHashSet<>(repository.findInstitutionIdsBySku(tenantId, sku.id()));
        if (scope != null) {
            ids.retainAll(scope);
            if (ids.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在或不适用于本机构");
            }
        }
        return ids.isEmpty() ? List.of() : repository.findInstitutionsByIds(tenantId, ids);
    }

    @Transactional(readOnly = true)
    public PageResponse<Institution> institutions(long tenantId, CurrentUser user, String keyword, String status,
                                                  int page, int size) {
        Set<Long> scope = institutionScope(user);
        int offset = (page - 1) * size;
        return new PageResponse<>(repository.searchInstitutions(tenantId, keyword, status, scope, offset, size),
                repository.countInstitutions(tenantId, keyword, status, scope), page, size);
    }

    @Transactional(readOnly = true)
    public List<Institution> activeInstitutionsByIds(long tenantId, CurrentUser user, Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> targetIds = new LinkedHashSet<>(ids);
        Set<Long> scope = institutionScope(user);
        if (scope != null) {
            targetIds.retainAll(scope);
        }
        return targetIds.isEmpty() ? List.of() : repository.findActiveInstitutionsByIds(tenantId, targetIds);
    }

    private List<PackageProject> validateProjects(long tenantId, List<PackageProjectItemRequest> requests) {
        List<PackageProject> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (PackageProjectItemRequest request : requests) {
            String group = request.optionGroup() == null || request.optionGroup().isBlank() ? "MAIN" : request.optionGroup().strip();
            if (!unique.add(request.projectId() + "|" + group)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一选项组中项目不能重复");
            }
            Project project = repository.findProject(tenantId, request.projectId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "子项目不存在: " + request.projectId()));
            if (!"ACTIVE".equals(project.status())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "子项目已停用: " + project.projectName());
            }
            long referencePrice = request.referencePriceFen() == null
                    ? project.listPriceFen() : request.referencePriceFen();
            result.add(new PackageProject(null, null, project.id(), project.projectCode(), project.projectName(),
                    project.categoryCode(), project.listPriceFen(), project.status(), request.grantCount(),
                    request.selectionWeight() == null ? Integer.valueOf(0) : request.selectionWeight(),
                    request.allocationPriceFen() == null ? Long.valueOf(0L) : request.allocationPriceFen(), group,
                    referencePrice, null));
        }
        return result;
    }

    private void validateDistinctProjects(List<PackageProject> projects) {
        Set<Long> projectIds = new HashSet<>();
        for (PackageProject project : projects) {
            if (!projectIds.add(project.projectId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "同一版本中子项目不能重复配置");
            }
        }
    }

    private List<PackageSelectionGroup> validateSelectionGroups(String selectionMode, int choiceCount,
                                                                 List<PackageProject> projects,
                                                                 List<PackageSelectionGroupRequest> requests) {
        validateSelection(selectionMode, choiceCount);
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        if (!"CHOOSE_N".equals(selectionMode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅自选项目套餐支持配置选项组");
        }
        List<PackageSelectionGroup> groups = requests.stream()
                .map(request -> new PackageSelectionGroup(null, null, request.groupCode().strip(),
                        request.groupName().strip(), request.minSelections(), request.maxSelections(),
                        request.sortOrder(), null))
                .toList();
        PackageSelectionRules.validateConfiguration(groups, projects);
        return groups;
    }

    private Set<Long> validateInstitutions(long tenantId, List<Long> ids) {
        Set<Long> result = new LinkedHashSet<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "机构 ID 不能为空");
                }
                result.add(id);
            }
        }
        if (!result.isEmpty() && repository.countActiveInstitutionsByIds(tenantId, result) != result.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "包含不存在或已停止合作的机构");
        }
        return result;
    }

    private void validateSelection(String mode, int count) {
        if (!Set.of("FIXED", "CHOOSE_N", "USE_N").contains(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未知选择模式");
        }
        if (!"FIXED".equals(mode) && count <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "选 N 项或 N 次时，N 必须大于 0");
        }
    }

    private Set<Long> institutionScope(CurrentUser user) {
        return user.isInstitutionUser() ? user.institutionIds() : null;
    }

    private Project requireProject(long tenantId, long id) {
        return repository.findProject(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "子项目不存在"));
    }
    private ProductPackage requirePackage(long tenantId, long id) {
        return repository.findPackage(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "套餐不存在"));
    }
    private PackageVersion requireVersion(long tenantId, long id) {
        return repository.findVersion(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "套餐版本不存在"));
    }
    private Sku requireSku(long tenantId, long id) {
        return repository.findSku(tenantId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在"));
    }
    private void requireDraft(PackageVersion version) {
        if (!"DRAFT".equals(version.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "已激活或已归档版本不可覆盖，请创建新版本");
        }
    }

    private void audit(long tenantId, CurrentUser user, String action, String type, long id, Object before, Object after) {
        auditService.record(tenantId, user.userId(), action, type, Long.toString(id), before, after, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
