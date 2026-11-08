package com.whale.crm.ai.infrastructure.mapper;

import com.whale.crm.ai.domain.DifyKnowledgeConfig;
import com.whale.crm.ai.domain.DifyKnowledgeConfigRepository;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.Optional;

@Mapper
public interface DifyKnowledgeConfigMapper extends DifyKnowledgeConfigRepository {
    @Override
    Optional<DifyKnowledgeConfig> find(@Param("tenantId") long tenantId);

    @Override
    Optional<DifyKnowledgeConfig> findForUpdate(@Param("tenantId") long tenantId);

    @Override
    void insert(@Param("tenantId") long tenantId, @Param("e") DifyKnowledgeConfig config);

    @Override
    int update(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
               @Param("e") DifyKnowledgeConfig config);

    @Override
    int updateConnection(@Param("tenantId") long tenantId, @Param("id") long id, @Param("version") long version,
                         @Param("status") String status, @Param("errorMasked") String errorMasked,
                         @Param("connectedAt") Instant connectedAt);
}
