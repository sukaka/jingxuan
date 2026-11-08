package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.Optional;

public interface DifyKnowledgeConfigRepository {
    Optional<DifyKnowledgeConfig> find(long tenantId);

    Optional<DifyKnowledgeConfig> findForUpdate(long tenantId);

    void insert(long tenantId, DifyKnowledgeConfig config);

    int update(long tenantId, long id, long version, DifyKnowledgeConfig config);

    int updateConnection(long tenantId, long id, long version, String status, String errorMasked,
                         Instant connectedAt);
}
