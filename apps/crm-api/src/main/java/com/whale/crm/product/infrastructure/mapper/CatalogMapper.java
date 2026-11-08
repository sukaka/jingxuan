package com.whale.crm.product.infrastructure.mapper;

import com.whale.crm.product.domain.Institution;
import com.whale.crm.product.domain.PackageProject;
import com.whale.crm.product.domain.PackageVersion;
import com.whale.crm.product.domain.ProductPackage;
import com.whale.crm.product.domain.Project;
import com.whale.crm.product.domain.Sku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Mapper
public interface CatalogMapper {
    List<Project> searchProjects(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                 @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    long countProjects(@Param("tenantId") long tenantId, @Param("keyword") String keyword, @Param("status") String status);
    Project selectProject(@Param("tenantId") long tenantId, @Param("id") long id);
    Project selectProjectByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    void insertProject(@Param("tenantId") long tenantId, @Param("entity") Project project);
    int updateProject(@Param("tenantId") long tenantId, @Param("entity") Project project);

    List<ProductPackage> searchPackages(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                        @Param("status") String status, @Param("offset") int offset, @Param("size") int size);
    long countPackages(@Param("tenantId") long tenantId, @Param("keyword") String keyword, @Param("status") String status);
    ProductPackage selectPackage(@Param("tenantId") long tenantId, @Param("id") long id);
    ProductPackage selectPackageForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    ProductPackage selectPackageByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    void insertPackage(@Param("tenantId") long tenantId, @Param("entity") ProductPackage productPackage);
    int updatePackage(@Param("tenantId") long tenantId, @Param("entity") ProductPackage productPackage);

    List<PackageVersion> selectVersions(@Param("tenantId") long tenantId, @Param("packageId") long packageId);
    PackageVersion selectVersion(@Param("tenantId") long tenantId, @Param("id") long id);
    PackageVersion selectVersionForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    int nextVersionNo(@Param("tenantId") long tenantId, @Param("packageId") long packageId);
    void insertVersion(@Param("tenantId") long tenantId, @Param("entity") PackageVersion version);
    int updateVersionDraft(@Param("tenantId") long tenantId, @Param("entity") PackageVersion version);
    int updateVersionStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                            @Param("expectedVersion") long expectedVersion, @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus);

    List<PackageProject> selectVersionProjects(@Param("tenantId") long tenantId,
                                               @Param("packageVersionId") long packageVersionId);
    void deleteVersionProjects(@Param("tenantId") long tenantId, @Param("packageVersionId") long packageVersionId);
    void insertVersionProject(@Param("tenantId") long tenantId, @Param("entity") PackageProject item);

    List<Sku> searchSkus(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                         @Param("status") String status, @Param("packageId") Long packageId,
                         @Param("institutionIds") Set<Long> institutionIds,
                         @Param("offset") int offset, @Param("size") int size);
    long countSkus(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                   @Param("status") String status, @Param("packageId") Long packageId,
                   @Param("institutionIds") Set<Long> institutionIds);
    List<Sku> selectSkusByVersion(@Param("tenantId") long tenantId, @Param("packageVersionId") long packageVersionId);
    Sku selectSku(@Param("tenantId") long tenantId, @Param("id") long id);
    Sku selectSkuByCode(@Param("tenantId") long tenantId, @Param("code") String code);
    void insertSku(@Param("tenantId") long tenantId, @Param("entity") Sku sku);
    int updateSku(@Param("tenantId") long tenantId, @Param("entity") Sku sku);
    int updateSkuStatus(@Param("tenantId") long tenantId, @Param("id") long id,
                        @Param("expectedVersion") long expectedVersion, @Param("status") String status,
                        @Param("at") Instant at);
    void disableSkusByVersion(@Param("tenantId") long tenantId, @Param("packageVersionId") long packageVersionId,
                              @Param("at") Instant at);
    void disableSkusByPackage(@Param("tenantId") long tenantId, @Param("packageId") long packageId,
                              @Param("at") Instant at);

    List<Institution> searchInstitutions(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                                         @Param("status") String status, @Param("institutionIds") Set<Long> institutionIds,
                                         @Param("offset") int offset, @Param("size") int size);
    long countInstitutions(@Param("tenantId") long tenantId, @Param("keyword") String keyword,
                           @Param("status") String status, @Param("institutionIds") Set<Long> institutionIds);
    List<Institution> selectInstitutionsByIds(@Param("tenantId") long tenantId, @Param("ids") Set<Long> ids);
    long countActiveInstitutionsByIds(@Param("tenantId") long tenantId, @Param("ids") Set<Long> ids);
    List<Long> selectInstitutionIdsBySku(@Param("tenantId") long tenantId, @Param("skuId") long skuId);
    void deleteSkuInstitutions(@Param("tenantId") long tenantId, @Param("skuId") long skuId);
    void insertSkuInstitution(@Param("tenantId") long tenantId, @Param("skuId") long skuId,
                              @Param("institutionId") long institutionId);
}
