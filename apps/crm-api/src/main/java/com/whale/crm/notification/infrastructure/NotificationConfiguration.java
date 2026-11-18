package com.whale.crm.notification.infrastructure;

import com.whale.crm.notification.application.NotificationDeliveryProperties;
import com.whale.crm.notification.infrastructure.wecom.WecomMessageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WecomMessageProperties.class, NotificationDeliveryProperties.class})
public class NotificationConfiguration {
}
