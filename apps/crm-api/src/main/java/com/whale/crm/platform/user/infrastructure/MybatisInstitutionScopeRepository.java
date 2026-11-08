package com.whale.crm.platform.user.infrastructure;

import com.whale.crm.platform.user.domain.InstitutionScopeRepository;
import com.whale.crm.platform.user.domain.InstitutionUserScope;
import com.whale.crm.platform.user.infrastructure.mapper.InstitutionUserMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MybatisInstitutionScopeRepository implements InstitutionScopeRepository {

    private final InstitutionUserMapper institutionUserMapper;

    public MybatisInstitutionScopeRepository(InstitutionUserMapper institutionUserMapper) {
        this.institutionUserMapper = institutionUserMapper;
    }

    @Override
    public List<Long> findActiveInstitutionIds(long tenantId, long userId) {
        return institutionUserMapper.selectActiveInstitutionIds(tenantId, userId);
    }

    @Override
    public List<InstitutionUserScope> findActiveScopes(long tenantId, long userId) {
        return institutionUserMapper.selectActiveScopes(tenantId, userId);
    }
}
