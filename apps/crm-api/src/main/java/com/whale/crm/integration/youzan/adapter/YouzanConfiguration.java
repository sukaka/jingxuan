package com.whale.crm.integration.youzan.adapter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class YouzanConfiguration {
    @Bean(name = "youzanSyncExecutor")
    public TaskExecutor youzanSyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("youzan-sync-");
        executor.setConcurrencyLimit(4);
        return executor;
    }

    @Bean(name = "youzanTaskScheduler")
    public TaskScheduler youzanTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("youzan-message-");
        return scheduler;
    }
}
