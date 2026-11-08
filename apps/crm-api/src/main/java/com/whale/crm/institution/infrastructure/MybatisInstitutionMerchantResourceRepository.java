package com.whale.crm.institution.infrastructure;

import com.whale.crm.institution.domain.InstitutionMerchantResource;
import com.whale.crm.institution.domain.InstitutionMerchantResourceRepository;
import com.whale.crm.institution.domain.InstitutionMerchantResourceSummary;
import com.whale.crm.institution.domain.InstitutionScope;
import com.whale.crm.institution.infrastructure.mapper.InstitutionMerchantResourceMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MybatisInstitutionMerchantResourceRepository implements InstitutionMerchantResourceRepository {
    private final InstitutionMerchantResourceMapper mapper;

    public MybatisInstitutionMerchantResourceRepository(InstitutionMerchantResourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(InstitutionMerchantResource resource) {
        mapper.insert(com.whale.crm.platform.context.TenantContext.currentTenantId(), resource);
    }

    @Override
    public Optional<InstitutionMerchantResource> findByResourceNo(long tenantId, String resourceNo) {
        return Optional.ofNullable(mapper.selectByResourceNo(tenantId, resourceNo));
    }

    @Override
    public Optional<InstitutionMerchantResource> find(long tenantId, long id) {
        return Optional.ofNullable(mapper.select(tenantId, id));
    }

    @Override
    public Optional<InstitutionMerchantResource> findForUpdate(long tenantId, long id) {
        return Optional.ofNullable(mapper.selectForUpdate(tenantId, id));
    }

    @Override
    public List<InstitutionMerchantResource> search(long tenantId, InstitutionScope scope, Long institutionId,
                                                     String resourceType, String status, String keyword,
                                                     int offset, int size) {
        return mapper.search(tenantId, scope, institutionId, resourceType, status, keyword, offset, size);
    }

    @Override
    public long count(long tenantId, InstitutionScope scope, Long institutionId,
                      String resourceType, String status, String keyword) {
        return mapper.count(tenantId, scope, institutionId, resourceType, status, keyword);
    }

    @Override
    public List<InstitutionMerchantResourceSummary> summarize(long tenantId, InstitutionScope scope) {
        return mapper.summarize(tenantId, scope);
    }

    @Override
    public int update(long tenantId, long id, long expectedVersion, String title, String subtitle,
                      String description, Long fileId, String status, long updatedBy) {
        return mapper.update(tenantId, id, expectedVersion, title, subtitle, description, fileId, status, updatedBy);
    }
}
