package com.whale.crm.product.infrastructure;

import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.Institution;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import com.whale.crm.product.infrastructure.mapper.CatalogMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class MybatisCatalogRepository implements CatalogRepository {
    private final CatalogMapper mapper;

    public MybatisCatalogRepository(CatalogMapper mapper) {
        this.mapper = mapper;
    }

    public List<Project> searchProjects(long tenantId, String keyword, String status, int offset, int size) {
        return mapper.searchProjects(tenantId, keyword, status, offset, size);
    }
    public long countProjects(long tenantId, String keyword, String status) {
        return mapper.countProjects(tenantId, keyword, status);
    }
    public Optional<Project> findProject(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectProject(tenantId, id));
    }
    public Optional<Project> findProjectByCode(long tenantId, String code) {
        return Optional.ofNullable(mapper.selectProjectByCode(tenantId, code));
    }
    public void insertProject(Project project) { mapper.insertProject(com.whale.crm.platform.context.TenantContext.currentTenantId(), project); }
    public int updateProject(Project project) { return mapper.updateProject(com.whale.crm.platform.context.TenantContext.currentTenantId(), project); }

    public List<ProductPackage> searchPackages(long tenantId, String keyword, String status, int offset, int size) {
        return mapper.searchPackages(tenantId, keyword, status, offset, size);
    }
    public long countPackages(long tenantId, String keyword, String status) {
        return mapper.countPackages(tenantId, keyword, status);
    }
    public Optional<ProductPackage> findPackage(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectPackage(tenantId, id));
    }
    public Optional<ProductPackage> findPackageForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectPackageForUpdate(tenantId, id));
    }
    public Optional<ProductPackage> findPackageByCode(long tenantId, String code) {
        return Optional.ofNullable(mapper.selectPackageByCode(tenantId, code));
    }
    public void insertPackage(ProductPackage productPackage) { mapper.insertPackage(com.whale.crm.platform.context.TenantContext.currentTenantId(), productPackage); }
    public int updatePackage(ProductPackage productPackage) { return mapper.updatePackage(com.whale.crm.platform.context.TenantContext.currentTenantId(), productPackage); }

    public List<PackageVersion> findVersions(long tenantId, long packageId) { return mapper.selectVersions(tenantId, packageId); }
    public Optional<PackageVersion> findVersion(long tenantId, long id) { return Optional.ofNullable(mapper.selectVersion(tenantId, id)); }
    public Optional<PackageVersion> findVersionForUpdate(long tenantId, long id) { return Optional.ofNullable(mapper.selectVersionForUpdate(tenantId, id)); }
    public int nextVersionNo(long tenantId, long packageId) { return mapper.nextVersionNo(tenantId, packageId); }
    public void insertVersion(PackageVersion version) { mapper.insertVersion(com.whale.crm.platform.context.TenantContext.currentTenantId(), version); }
    public int updateVersionDraft(PackageVersion version) { return mapper.updateVersionDraft(com.whale.crm.platform.context.TenantContext.currentTenantId(), version); }
    public int updateVersionStatus(long tenantId, long id, long expectedVersion, String fromStatus, String toStatus) {
        return mapper.updateVersionStatus(tenantId, id, expectedVersion, fromStatus, toStatus);
    }

    public List<PackageProject> findVersionProjects(long tenantId, long packageVersionId) { return mapper.selectVersionProjects(tenantId, packageVersionId); }
    public void deleteVersionProjects(long tenantId, long packageVersionId) { mapper.deleteVersionProjects(tenantId, packageVersionId); }
    public void insertVersionProject(PackageProject item) { mapper.insertVersionProject(com.whale.crm.platform.context.TenantContext.currentTenantId(), item); }

    public List<Sku> searchSkus(long tenantId, String keyword, String status, Long packageId, Set<Long> institutionIds, int offset, int size) {
        return mapper.searchSkus(tenantId, keyword, status, packageId, institutionIds, offset, size);
    }
    public long countSkus(long tenantId, String keyword, String status, Long packageId, Set<Long> institutionIds) {
        return mapper.countSkus(tenantId, keyword, status, packageId, institutionIds);
    }
    public List<Sku> findSkusByVersion(long tenantId, long packageVersionId) { return mapper.selectSkusByVersion(tenantId, packageVersionId); }
    public Optional<Sku> findSku(long tenantId, long id) { return Optional.ofNullable(mapper.selectSku(tenantId, id)); }
    public Optional<Sku> findSkuByCode(long tenantId, String code) { return Optional.ofNullable(mapper.selectSkuByCode(tenantId, code)); }
    public void insertSku(Sku sku) { mapper.insertSku(com.whale.crm.platform.context.TenantContext.currentTenantId(), sku); }
    public int updateSku(Sku sku) { return mapper.updateSku(com.whale.crm.platform.context.TenantContext.currentTenantId(), sku); }
    public int updateSkuStatus(long tenantId, long id, long expectedVersion, String status, Instant at) {
        return mapper.updateSkuStatus(tenantId, id, expectedVersion, status, at);
    }
    public void disableSkusByVersion(long tenantId, long packageVersionId, Instant at) { mapper.disableSkusByVersion(tenantId, packageVersionId, at); }
    public void disableSkusByPackage(long tenantId, long packageId, Instant at) { mapper.disableSkusByPackage(tenantId, packageId, at); }

    public List<Institution> searchInstitutions(long tenantId, String keyword, String status, Set<Long> institutionIds, int offset, int size) {
        return mapper.searchInstitutions(tenantId, keyword, status, institutionIds, offset, size);
    }
    public long countInstitutions(long tenantId, String keyword, String status, Set<Long> institutionIds) {
        return mapper.countInstitutions(tenantId, keyword, status, institutionIds);
    }
    public List<Institution> findInstitutionsByIds(long tenantId, Set<Long> ids) { return mapper.selectInstitutionsByIds(tenantId, ids); }
    public long countActiveInstitutionsByIds(long tenantId, Set<Long> ids) { return mapper.countActiveInstitutionsByIds(tenantId, ids); }
    public List<Long> findInstitutionIdsBySku(long tenantId, long skuId) { return mapper.selectInstitutionIdsBySku(tenantId, skuId); }
    public void deleteSkuInstitutions(long tenantId, long skuId) { mapper.deleteSkuInstitutions(tenantId, skuId); }
    public void insertSkuInstitution(long tenantId, long skuId, long institutionId) { mapper.insertSkuInstitution(tenantId, skuId, institutionId); }
}
