package com.whale.crm.integration.youzan.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class YouzanAdapterRouter {
    private final ObjectProvider<MockYouzanAdapter> mockAdapter;
    private final DisabledYouzanAdapter disabledAdapter;
    private final RealYouzanAdapter realAdapter;
    private final YouzanConfigRepository configRepository;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public YouzanAdapterRouter(ObjectProvider<MockYouzanAdapter> mockAdapter, DisabledYouzanAdapter disabledAdapter,
                               RealYouzanAdapter realAdapter, YouzanConfigRepository configRepository,
                               IntegrationRuntimeConfigService runtimeConfig) {
        this.mockAdapter = mockAdapter;
        this.disabledAdapter = disabledAdapter;
        this.realAdapter = realAdapter;
        this.configRepository = configRepository;
        this.runtimeConfig = runtimeConfig;
    }

    public YouzanAdapter adapter(long tenantId, String shopId) {
        if (runtimeConfig.youzanRealEnabled(tenantId)) {
            return configRepository.findShopConfig(tenantId, shopId)
                    .filter(config -> Boolean.TRUE.equals(config.syncEnabled()))
                    .<YouzanAdapter>map(config -> realAdapter)
                    .orElseGet(DisabledYouzanAdapter::shopNotReady);
        }
        MockYouzanAdapter mock = mockAdapter.getIfAvailable();
        return mock == null ? disabledAdapter : mock;
    }

    public YouzanAdapter webhookAdapter(long tenantId) {
        if (runtimeConfig.youzanRealEnabled(tenantId)) {
            return DisabledYouzanAdapter.officialWebhookOnly();
        }
        MockYouzanAdapter mock = mockAdapter.getIfAvailable();
        return mock == null ? disabledAdapter : mock;
    }
}
