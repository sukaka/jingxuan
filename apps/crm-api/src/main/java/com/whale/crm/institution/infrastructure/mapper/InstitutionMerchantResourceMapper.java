package com.whale.crm.institution.infrastructure.mapper;

import com.whale.crm.institution.domain.InstitutionMerchantResource;
import com.whale.crm.institution.domain.InstitutionMerchantResourceSummary;
import com.whale.crm.institution.domain.InstitutionScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InstitutionMerchantResourceMapper {
    void insert(@Param("tenantId") long tenantId, @Param("entity") InstitutionMerchantResource resource);
    InstitutionMerchantResource selectByResourceNo(@Param("tenantId") long tenantId,
                                                   @Param("resourceNo") String resourceNo);
    InstitutionMerchantResource select(@Param("tenantId") long tenantId, @Param("id") long id);
    InstitutionMerchantResource selectForUpdate(@Param("tenantId") long tenantId, @Param("id") long id);
    List<InstitutionMerchantResource> search(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
                                             @Param("institutionId") Long institutionId,
                                             @Param("resourceType") String resourceType,
                                             @Param("status") String status, @Param("keyword") String keyword,
                                             @Param("offset") int offset, @Param("size") int size);
    long count(@Param("tenantId") long tenantId, @Param("scope") InstitutionScope scope,
               @Param("institutionId") Long institutionId, @Param("resourceType") String resourceType,
               @Param("status") String status, @Param("keyword") String keyword);
    List<InstitutionMerchantResourceSummary> summarize(@Param("tenantId") long tenantId,
                                                        @Param("scope") InstitutionScope scope);
    int update(@Param("tenantId") long tenantId, @Param("id") long id,
               @Param("expectedVersion") long expectedVersion, @Param("title") String title,
               @Param("subtitle") String subtitle, @Param("description") String description,
               @Param("fileId") Long fileId, @Param("status") String status, @Param("updatedBy") long updatedBy);
}
