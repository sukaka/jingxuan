package com.whale.crm.integration.wecom.tag.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 企微客户标签异步推送任务定时处理器（受 app.scheduling.enabled 与 app.wecom.tag-sync.worker-enabled 双重控制）。
 */
@Component
public class WecomTagPushTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(WecomTagPushTaskScheduler.class);

    private final WecomTagSyncService tagSyncService;
    private final boolean workerEnabled;

    public WecomTagPushTaskScheduler(WecomTagSyncService tagSyncService,
                                     @Value("${app.wecom.tag-sync.worker-enabled:true}") boolean workerEnabled) {
        this.tagSyncService = tagSyncService;
        this.workerEnabled = workerEnabled;
    }

    @Scheduled(fixedDelayString = "${app.wecom.tag-sync.push-delay-ms:5000}",
            initialDelayString = "${app.wecom.tag-sync.push-initial-delay-ms:5000}")
    public void pollAndPush() {
        if (!workerEnabled) {
            return;
        }
        try {
            tagSyncService.processPendingPushTasks();
        } catch (RuntimeException ex) {
            log.warn("企微客户标签异步推送轮询执行异常，下个周期重试");
        }
    }
}
