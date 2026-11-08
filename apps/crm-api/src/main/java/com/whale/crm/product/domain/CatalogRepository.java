package com.whale.crm.product.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CatalogRepository {

    List<Project> searchProjects(long tenantId, String keyword, String status, int offset, int size);
    long countProjects(long tenantId, String keyword, String status);
    Optional<Project> findProject(long tenantId, long id);
    Optional<Project> findProjectByCode(long tenantId, String code);
    void insertProject(Project project);
    int updateProject(Project project);

    List<ProductPackage> searchPackages(long tenantId, String keyword, String status, int offset, int size);
    long countPackages(long tenantId, String keyword, String status);
    Optional<ProductPackage> findPackage(long tenantId, long id);
    Optional<ProductPackage> findPackageForUpdate(long tenantId, long id);
    Optional<ProductPackage> findPackageByCode(long tenantId, String code);
    void insertPackage(ProductPackage productPackage);
    int updatePackage(ProductPackage productPackage);

    List<PackageVersion> findVersions(long tenantId, long packageId);
    Optional<PackageVersion> findVersion(long tenantId, long id);
    Optional<PackageVersion> findVersionForUpdate(long tenantId, long id);
    int nextVersionNo(long tenantId, long packageId);
    void insertVersion(PackageVersion version);
    int updateVersionDraft(PackageVersion version);
    int updateVersionStatus(long tenantId, long id, long expectedVersion, String fromStatus, String toStatus);

    List<PackageProject> findVersionProjects(long tenantId, long packageVersionId);
    void deleteVersionProjects(long tenantId, long packageVersionId);
    void insertVersionProject(PackageProject item);

    List<Sku> searchSkus(long tenantId, String keyword, String status, Long packageId,
                         Set<Long> institutionIds, int offset, int size);
    long countSkus(long tenantId, String keyword, String status, Long packageId, Set<Long> institutionIds);
    List<Sku> findSkusByVersion(long tenantId, long packageVersionId);
    Optional<Sku> findSku(long tenantId, long id);
    Optional<Sku> findSkuByCode(long tenantId, String code);
    void insertSku(Sku sku);
    int updateSku(Sku sku);
    int updateSkuStatus(long tenantId, long id, long expectedVersion, String status, Instant at);
    void disableSkusByVersion(long tenantId, long packageVersionId, Instant at);
    void disableSkusByPackage(long tenantId, long packageId, Instant at);

    List<Institution> searchInstitutions(long tenantId, String keyword, String status,
                                         Set<Long> institutionIds, int offset, int size);
    long countInstitutions(long tenantId, String keyword, String status, Set<Long> institutionIds);
    List<Institution> findInstitutionsByIds(long tenantId, Set<Long> ids);
    long countActiveInstitutionsByIds(long tenantId, Set<Long> ids);
    List<Long> findInstitutionIdsBySku(long tenantId, long skuId);
    void deleteSkuInstitutions(long tenantId, long skuId);
    void insertSkuInstitution(long tenantId, long skuId, long institutionId);
}
