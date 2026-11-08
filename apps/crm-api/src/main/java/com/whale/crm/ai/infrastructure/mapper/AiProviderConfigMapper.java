package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.AiProviderConfig;
import com.whale.crm.ai.domain.AiProviderConfigRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface AiProviderConfigMapper extends AiProviderConfigRepository {
    @Override
    Optional<AiProviderConfig> find(@Param("tenantId") long tenantId);

    @Override
    Optional<AiProviderConfig> findForUpdate(@Param("tenantId") long tenantId);

    @Override
    void insert(@Param("tenantId") long tenantId, @Param("e") AiProviderConfig config);

    @Override
    int update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
               @Param("e") AiProviderConfig config);

    @Override
    int updateConnection(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("status") String status, @Param("errorMasked") String errorMasked,
                         @Param("connectedAt") Instant connectedAt);
}
