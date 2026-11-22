package com.whale.crm.integration.config;

import com.whale.crm.integration.config.application.IntegrationRuntimeConfigService;
import com.whale.crm.integration.wecom.adapter.DisabledWecomAdapter;
import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.integration.wecom.adapter.MockWecomContactClient;
import com.whale.crm.integration.wecom.adapter.OfficialWecomContactClient;
import com.whale.crm.integration.wecom.adapter.WecomAdapterRouter;
import com.whale.crm.integration.wecom.adapter.WecomContactClientRouter;
import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.youzan.adapter.DisabledYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.MockYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.RealYouzanAdapter;
import com.whale.crm.integration.youzan.adapter.YouzanAdapterRouter;
import com.whale.crm.integration.youzan.domain.YouzanConfigRepository;
import com.whale.crm.integration.youzan.domain.YouzanShopConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationRuntimeRouterTest {
    @Test
    void youzanRouterReadsCurrentTenantSwitchOnEveryCall() {
        ObjectProvider<MockYouzanAdapter> mocks = provider(mock(MockYouzanAdapter.class));
        RealYouzanAdapter real = mock(RealYouzanAdapter.class);
        YouzanConfigRepository configs = mock(YouzanConfigRepository.class);
        IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
        when(configs.findShopConfig(2L, "shop")).thenReturn(Optional.of(new YouzanShopConfig(1L, 2L, "shop",
                "测试店", "client", "secret", null, null, null, "CONNECTED", true, null, null, null, 0L)));
        when(runtime.youzanRealEnabled(2L)).thenReturn(false, true);
        YouzanAdapterRouter router = new YouzanAdapterRouter(mocks, mock(DisabledYouzanAdapter.class), real, configs, runtime);

        assertThat(router.adapter(2L, "shop")).isInstanceOf(MockYouzanAdapter.class);
        assertThat(router.adapter(2L, "shop")).isSameAs(real);
    }

    @Test
    void wecomRoutersSwitchWithoutRebuildingSpringContext() {
        IntegrationRuntimeConfigService runtime = mock(IntegrationRuntimeConfigService.class);
        OfficialWecomContactClient official = mock(OfficialWecomContactClient.class);
        MockWecomContactClient mockContact = mock(MockWecomContactClient.class);
        when(runtime.wecomOfficialEnabled(1L)).thenReturn(false, true);
        when(runtime.wecomOfficialEnabled(2L)).thenReturn(true);
        WecomOfficialProperties officialProperties = new WecomOfficialProperties(1L, true, "corp", "secret",
                "https://qyapi.weixin.qq.com", "token", "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                3000, 10000, 100, 100);
        WecomContactClientRouter contacts = new WecomContactClientRouter(
                provider(mockContact), provider(official), officialProperties, runtime);
        assertThat(contacts.client(1L)).isSameAs(mockContact);
        assertThat(contacts.client(1L)).isSameAs(official);
        assertThat(contacts.client(2L)).isNull();

        DisabledWecomAdapter disabled = mock(DisabledWecomAdapter.class);
        MockWecomAdapter mockArchive = mock(MockWecomAdapter.class);
        com.whale.crm.integration.wecom.adapter.OfficialWecomArchiveAdapter officialArchive =
                mock(com.whale.crm.integration.wecom.adapter.OfficialWecomArchiveAdapter.class);
        com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties archiveProperties =
                new com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties(1L, "corp", "secret",
                        "sdk", "keys", "", "", 15);
        when(runtime.wecomArchiveRealEnabled(1L)).thenReturn(false, true);
        when(runtime.wecomArchiveRealEnabled(2L)).thenReturn(true);
        WecomAdapterRouter archive = new WecomAdapterRouter(provider(mockArchive), disabled, officialArchive,
                archiveProperties, runtime);
        assertThat(archive.adapter(1L)).isSameAs(mockArchive);
        assertThat(archive.adapter(1L)).isSameAs(officialArchive);
        assertThat(archive.adapter(2L)).isSameAs(disabled);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
