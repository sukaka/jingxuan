package com.whale.crm.integration.youzan.customer.adapter;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 有赞客户拉取适配器路由器：
 * 开启 youzanRealEnabled 时必须命中已启用同步的真实店铺配置，绝不静默降级或伪造真实有赞返回。
 */
@Component
public class YouzanCustomerPullClientRouter {

    private final ObjectProvider<MockYouzanCustomerPullClient> mockClient;
    private final OfficialYouzanCustomerPullClient officialClient;
    private final YouzanConfigRepository configRepository;
    private final IntegrationRuntimeConfigService runtimeConfig;

    public YouzanCustomerPullClientRouter(ObjectProvider<MockYouzanCustomerPullClient> mockClient,
                                          OfficialYouzanCustomerPullClient officialClient,
                                          YouzanConfigRepository configRepository,
                                          IntegrationRuntimeConfigService runtimeConfig) {
        this.mockClient = mockClient;
        this.officialClient = officialClient;
        this.configRepository = configRepository;
        this.runtimeConfig = runtimeConfig;
    }

    public YouzanCustomerPullClient resolve(long tenantId, String shopId) {
        if (runtimeConfig.youzanRealEnabled(tenantId)) {
            boolean shopReady = configRepository.findShopConfig(tenantId, shopId)
                    .filter(cfg -> Boolean.TRUE.equals(cfg.syncEnabled()))
                    .isPresent();
            if (!shopReady) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "有赞真实同步已开启，但当前店铺尚未完成配置或未启用同步开关（shopId=" + shopId + "）");
            }
            return officialClient;
        }
        MockYouzanCustomerPullClient mock = mockClient.getIfAvailable();
        if (mock != null) {
            return mock;
        }
        throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE,
                "当前未开启真实有赞连接，请先在「有赞订单同步」开启真实同步开关并配置店铺授权");
    }

    public boolean isOfficialEnabled(long tenantId) {
        return runtimeConfig.youzanRealEnabled(tenantId);
    }

    public String activeAdapterName(long tenantId, String shopId) {
        if (runtimeConfig.youzanRealEnabled(tenantId)) {
            return officialClient.name();
        }
        MockYouzanCustomerPullClient mock = mockClient.getIfAvailable();
        return mock != null ? mock.name() : "DISABLED";
    }
}
