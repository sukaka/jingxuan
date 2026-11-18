package com.whale.crm.institution.infrastructure.mapper;

import com.whale.crm.institution.domain.InstitutionSettlementConfig;
import com.whale.crm.institution.domain.InstitutionSettlementConfigRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface InstitutionSettlementConfigMapper extends InstitutionSettlementConfigRepository {
    @Override
    Optional<InstitutionSettlementConfig> find(@Param("tenantId") long tenantId);

    @Override
    Optional<InstitutionSettlementConfig> findForUpdate(@Param("tenantId") long tenantId);

    @Override
    void insert(@Param("tenantId") long tenantId, @Param("entity") InstitutionSettlementConfig config);

    @Override
    int update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
               @Param("entity") InstitutionSettlementConfig config);
}
