package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class WecomContactClientRouter {
    private final ObjectProvider<MockWecomContactClient> mock;
    private final ObjectProvider<OfficialWecomContactClient> official;
    private final WecomOfficialProperties officialProperties;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public WecomContactClientRouter(ObjectProvider<MockWecomContactClient> mock,
                                    ObjectProvider<OfficialWecomContactClient> official,
                                    WecomOfficialProperties officialProperties,
                                    IntegrationRuntimeConfigService runtimeConfig) {
        this.mock = mock;
        this.official = official;
        this.officialProperties = officialProperties;
        this.runtimeConfig = runtimeConfig;
    }

    public WecomContactClient client(long tenantId) {
        if (runtimeConfig.wecomOfficialEnabled(tenantId)) {
            return officialProperties.allowedTenantId() > 0 && officialProperties.allowedTenantId() == tenantId
                    ? official.getIfAvailable() : null;
        }
        return mock.getIfAvailable();
    }
}
