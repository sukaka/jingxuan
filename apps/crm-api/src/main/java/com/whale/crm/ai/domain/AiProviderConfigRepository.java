package com.whale.crm.ai.domain;

import java.time.Instant;
import java.util.Optional;

public interface AiProviderConfigRepository {
    Optional<AiProviderConfig> find(long tenantId);

    Optional<AiProviderConfig> findForUpdate(long tenantId);

    void insert(long tenantId, AiProviderConfig config);

    int update(long tenantId, long id, long version, AiProviderConfig config);

    int updateConnection(long tenantId, long id, long version, String status, String errorMasked,
                         Instant connectedAt);
}
