package com.whale.crm.integration.config.domain;

public record IntegrationRuntimeConfig(Long id, long tenantId, boolean youzanRealEnabled,
                                       boolean wecomArchiveRealEnabled, boolean wecomOfficialEnabled,
                                       long version) {
}
