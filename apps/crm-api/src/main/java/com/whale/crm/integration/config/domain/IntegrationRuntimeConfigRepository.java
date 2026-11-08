package com.whale.crm.integration.config.domain;

import java.util.Optional;

public interface IntegrationRuntimeConfigRepository {
    Optional<IntegrationRuntimeConfig> find(long tenantId);

    Optional<IntegrationRuntimeConfig> findForUpdate(long tenantId);

    void insert(long tenantId, IntegrationRuntimeConfig config);

    int update(long tenantId, long id, long version, IntegrationRuntimeConfig config);
}
