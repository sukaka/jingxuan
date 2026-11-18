package com.whale.crm.integration.wecom.adapter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WecomOfficialProperties.class, WecomArchiveOfficialProperties.class,
        com.whale.crm.integration.wecom.media.adapter.WecomMediaProperties.class})
public class WecomOfficialConfiguration {
}
