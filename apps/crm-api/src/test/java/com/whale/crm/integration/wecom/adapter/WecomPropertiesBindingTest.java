package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.notification.infrastructure.wecom.WecomMessageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WecomPropertiesBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindingConfig.class);

    @Test
    void constructorBindingBindsAllowedTenantIdForOfficialAndMessageProperties() {
        runner.withPropertyValues(
                        "app.wecom.official.allowed-tenant-id=2",
                        "app.wecom.official.enabled=true",
                        "app.wecom.official.corp-id=ww-t2",
                        "app.wecom.official.secret=sec-t2",
                        "app.wecom.official.api-base-url=https://qyapi.weixin.qq.com",
                        "app.wecom.official.callback-token=tok",
                        "app.wecom.official.callback-aes-key=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG",
                        "app.wecom.official.connect-timeout-ms=3000",
                        "app.wecom.official.read-timeout-ms=10000",
                        "app.wecom.official.page-size=100",
                        "app.wecom.official.max-pages=50",
                        "app.wecom.message.allowed-tenant-id=3",
                        "app.wecom.message.mode=OFFICIAL",
                        "app.wecom.message.corp-id=ww-msg-3",
                        "app.wecom.message.secret=msg-sec-3",
                        "app.wecom.message.agent-id=10003",
                        "app.wecom.message.api-base-url=https://qyapi.weixin.qq.com",
                        "app.wecom.message.connect-timeout-ms=3000",
                        "app.wecom.message.read-timeout-ms=10000",
                        "app.wecom.message.token-refresh-skew-seconds=60")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    WecomOfficialProperties official = context.getBean(WecomOfficialProperties.class);
                    WecomMessageProperties message = context.getBean(WecomMessageProperties.class);
                    assertThat(official.allowedTenantId()).isEqualTo(2L);
                    assertThat(official.corpId()).isEqualTo("ww-t2");
                    assertThat(message.allowedTenantId()).isEqualTo(3L);
                    assertThat(message.agentId()).isEqualTo(10003L);
                });
    }

    @EnableConfigurationProperties({WecomOfficialProperties.class, WecomMessageProperties.class})
    static class BindingConfig {
    }
}
