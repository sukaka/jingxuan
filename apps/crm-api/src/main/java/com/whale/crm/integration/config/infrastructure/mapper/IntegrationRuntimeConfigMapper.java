package com.whale.crm.integration.config.infrastructure.mapper;

import com.whale.crm.integration.config.domain.IntegrationRuntimeConfig;
import com.whale.crm.integration.config.domain.IntegrationRuntimeConfigRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface IntegrationRuntimeConfigMapper extends IntegrationRuntimeConfigRepository {
    @Override
    Optional<IntegrationRuntimeConfig> find(@Param("tenantId") long tenantId);

    @Override
    Optional<IntegrationRuntimeConfig> findForUpdate(@Param("tenantId") long tenantId);

    @Override
    void insert(@Param("tenantId") long tenantId, @Param("e") IntegrationRuntimeConfig config);

    @Override
    int update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
               @Param("e") IntegrationRuntimeConfig config);
}
