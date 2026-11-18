package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class WecomAdapterRouter {
    private final ObjectProvider<MockWecomAdapter> mock;
    private final DisabledWecomAdapter disabled;
    private final OfficialWecomArchiveAdapter official;
    private final WecomArchiveOfficialProperties properties;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public WecomAdapterRouter(ObjectProvider<MockWecomAdapter> mock, DisabledWecomAdapter disabled,
                              OfficialWecomArchiveAdapter official, WecomArchiveOfficialProperties properties,
                              IntegrationRuntimeConfigService runtimeConfig) {
        this.mock = mock;
        this.disabled = disabled;
        this.official = official;
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
    }

    public WecomAdapter adapter(long tenantId) {
        if (runtimeConfig.wecomArchiveRealEnabled(tenantId)) {
            return properties.allowedTenantId() == tenantId ? official : disabled;
        }
        return mock.getIfAvailable() == null ? disabled : mock.getIfAvailable();
    }
}
