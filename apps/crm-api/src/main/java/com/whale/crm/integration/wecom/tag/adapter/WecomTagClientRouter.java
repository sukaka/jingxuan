package com.whale.crm.integration.wecom.tag.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 企微企业标签客户端路由：与 WecomContactClientRouter 保持一致，
 * 按租户运行时开关在官方接口（OfficialWecomTagClient）与本地开发 Mock 间切换。
 */
@Component
public class WecomTagClientRouter {

    private final ObjectProvider<MockWecomTagClient> mockProvider;
    private final ObjectProvider<OfficialWecomTagClient> officialProvider;
    private final WecomOfficialProperties officialProperties;
    private final IntegrationRuntimeConfigService runtimeConfigService;

    public WecomTagClientRouter(ObjectProvider<MockWecomTagClient> mockProvider,
                                ObjectProvider<OfficialWecomTagClient> officialProvider,
                                WecomOfficialProperties officialProperties,
                                IntegrationRuntimeConfigService runtimeConfigService) {
        this.mockProvider = mockProvider;
        this.officialProvider = officialProvider;
        this.officialProperties = officialProperties;
        this.runtimeConfigService = runtimeConfigService;
    }

    public WecomTagClient client(long tenantId) {
        if (runtimeConfigService.wecomOfficialEnabled(tenantId)) {
            return officialProperties.allowedTenantId() > 0 && officialProperties.allowedTenantId() == tenantId
                    ? officialProvider.getIfAvailable() : null;
        }
        return mockProvider.getIfAvailable();
    }

    public boolean isOfficialEnabled(long tenantId) {
        return runtimeConfigService.wecomOfficialEnabled(tenantId);
    }
}
