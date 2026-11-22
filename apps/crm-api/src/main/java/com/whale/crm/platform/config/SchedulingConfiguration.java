package com.whale.crm.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全局定时任务配置：默认开启，仅在 CLI 引导（如 {@code BootstrapAdminCommand}）时通过
 * {@code app.scheduling.enabled=false} 关闭所有 {@code @Scheduled} 任务。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
