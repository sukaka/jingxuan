package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class WecomContactClientRouter {
    private final ObjectProvider<MockWecomContactClient> mock;
    private final ObjectProvider<OfficialWecomContactClient> official;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public WecomContactClientRouter(ObjectProvider<MockWecomContactClient> mock,
                                    ObjectProvider<OfficialWecomContactClient> official,
                                    IntegrationRuntimeConfigService runtimeConfig) {
        this.mock = mock;
        this.official = official;
        this.runtimeConfig = runtimeConfig;
    }

    public WecomContactClient client(long tenantId) {
        if (runtimeConfig.wecomOfficialEnabled(tenantId)) {
            return official.getIfAvailable();
        }
        return mock.getIfAvailable();
    }
}
