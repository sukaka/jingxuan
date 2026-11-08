package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class WecomAdapterRouter {
    private final ObjectProvider<MockWecomAdapter> mock;
    private final DisabledWecomAdapter disabled;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public WecomAdapterRouter(ObjectProvider<MockWecomAdapter> mock, DisabledWecomAdapter disabled,
                              IntegrationRuntimeConfigService runtimeConfig) {
        this.mock = mock;
        this.disabled = disabled;
        this.runtimeConfig = runtimeConfig;
    }

    public WecomAdapter adapter(long tenantId) {
        if (runtimeConfig.wecomArchiveRealEnabled(tenantId)) {
            return disabled;
        }
        return mock.getIfAvailable() == null ? disabled : mock.getIfAvailable();
    }
}
