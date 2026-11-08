package com.whale.crm.institution.domain;

import java.util.List;
import java.util.Optional;

public interface InstitutionMerchantResourceRepository {
    void insert(InstitutionMerchantResource resource);
    Optional<InstitutionMerchantResource> findByResourceNo(long tenantId, String resourceNo);
    Optional<InstitutionMerchantResource> find(long tenantId, long id);
    Optional<InstitutionMerchantResource> findForUpdate(long tenantId, long id);
    List<InstitutionMerchantResource> search(long tenantId, InstitutionScope scope, Long institutionId,
                                             String resourceType, String status, String keyword, int offset, int size);
    long count(long tenantId, InstitutionScope scope, Long institutionId,
               String resourceType, String status, String keyword);
    List<InstitutionMerchantResourceSummary> summarize(long tenantId, InstitutionScope scope);
    int update(long tenantId, long id, long expectedVersion, String title, String subtitle,
               String description, Long fileId, String status, long updatedBy);
}
